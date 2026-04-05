package com.example.movesensedatarecorder.service;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcelable;
import android.util.Log;

import com.example.movesensedatarecorder.model.DataPoint;
import com.example.movesensedatarecorder.utils.DataUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static com.example.movesensedatarecorder.service.GattActions.*;
import static com.example.movesensedatarecorder.service.UUIDs.CLIENT_CHARACTERISTIC_CONFIG;
import static com.example.movesensedatarecorder.service.UUIDs.MOVESENSE_2_0_COMMAND_CHARACTERISTIC;
import static com.example.movesensedatarecorder.service.UUIDs.MOVESENSE_2_0_DATA_CHARACTERISTIC;
import static com.example.movesensedatarecorder.service.UUIDs.MOVESENSE_2_0_SERVICE;
import static com.example.movesensedatarecorder.service.UUIDs.IMU_COMMAND;
import static com.example.movesensedatarecorder.service.UUIDs.MOVESENSE_RESPONSE;
import static com.example.movesensedatarecorder.service.UUIDs.REQUEST_ID;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class BleIMUService extends Service {

        private final static String TAG = BleIMUService.class.getSimpleName();

        // ---------------- BoardLog V1.1 ----------------
        public static final String ACTION_START = "boardlog.START";
        public static final String ACTION_LAP = "boardlog.LAP";
        public static final String ACTION_TOGGLE_PAUSE = "boardlog.TOGGLE_PAUSE";
        public static final String ACTION_STOP = "boardlog.STOP";

        private static final String NOTIF_CHANNEL_ID = "boardlog_recording";
        private static final int NOTIF_ID = 1001;

        private enum RecState { IDLE, RECORDING, PAUSED }
        private RecState recState = RecState.IDLE;

        private long startUtcMs = 0L;
        private long startMonoNs = 0L;
        private long pausedStartMonoNs = 0L;
        private long pausedTotalMonoNs = 0L;

        private int imuSamples = 0;
        private int lapCount = 0;

        private BufferedWriter ndjsonWriter = null;
        private File ndjsonFile = null;

        // Session metadata (hardcode for now; later pass as Intent extras from UI)
        private String sport = "surf";
        private String env = "water";
        private String stance = "regular";
        private String mountFace = "top";
        private String mountRule = "logo_readable_horizontal_facing_nose";
        private int rateHz = 52;
        // ------------------------------------------------

        private BluetoothManager mBluetoothManager;
        private BluetoothAdapter mBluetoothAdapter;
        private String mBluetoothDeviceAddress;
        private BluetoothGatt mBluetoothGatt;

        private BluetoothGattService movesenseService = null;
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    "BoardLog Recording",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String status) {
        return new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("BoardLog")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build();
    }

    private void startAsForeground(String status) {
        startForeground(NOTIF_ID, buildNotification(status));
    }

    private void updateForeground(String status) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(status));
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if (ACTION_START.equals(a)) startRecording();
            else if (ACTION_LAP.equals(a)) addLap();
            else if (ACTION_TOGGLE_PAUSE.equals(a)) togglePause();
            else if (ACTION_STOP.equals(a)) stopRecording();
        }
        return START_STICKY;
    }
    private long nowMonoNs() {
        return SystemClock.elapsedRealtimeNanos();
    }

    private long utcFromMono(long monoNs) {
        long deltaMs = (monoNs - startMonoNs) / 1_000_000L;
        return startUtcMs + deltaMs;
    }

    private void writeLine(JSONObject obj) throws Exception {
        if (ndjsonWriter == null) return;
        ndjsonWriter.write(obj.toString());
        ndjsonWriter.newLine();
    }

    private void openNdjson() throws Exception {
        startUtcMs = System.currentTimeMillis();
        startMonoNs = nowMonoNs();

        String sessionId = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss'Z'", Locale.US)
                .format(new Date(startUtcMs)) + "__board";
        String fileName = "BoardLog__" + sessionId + ".ndjson";

        File dir = getExternalFilesDir(null);
        if (dir == null) {
            throw new IllegalStateException("External files dir is null");
        }

        ndjsonFile = new File(dir, fileName);

        ndjsonWriter = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(ndjsonFile, true), StandardCharsets.UTF_8),
                64 * 1024
        );

        JSONObject session = new JSONObject();
        session.put("type", "session");
        session.put("schema", 1);
        session.put("app", "BoardLog");
        session.put("app_ver", "0.1");
        session.put("session_id", sessionId);
        session.put("sport", sport);
        session.put("env", env);
        session.put("stance", stance);

        JSONObject mount = new JSONObject();
        mount.put("face", mountFace);
        mount.put("rule", mountRule);
        session.put("mount", mount);

        JSONObject sensor = new JSONObject();
        sensor.put("vendor", "Movesense");
        sensor.put("mac", mBluetoothDeviceAddress == null ? "" : mBluetoothDeviceAddress);
        sensor.put("loc", "board");
        session.put("sensor", sensor);

        JSONObject start = new JSONObject();
        start.put("utc_ms", startUtcMs);
        start.put("mono_ns", startMonoNs);
        session.put("start", start);

        session.put("rate_hz", rateHz);

        writeLine(session);

        JSONObject ev = new JSONObject();
        ev.put("type", "event");
        ev.put("name", "recording_started");
        ev.put("utc_ms", startUtcMs);
        ev.put("mono_ns", startMonoNs);
        writeLine(ev);

        ndjsonWriter.flush();
    }

    private void safeCloseWriter() {
        try { if (ndjsonWriter != null) ndjsonWriter.flush(); } catch (Exception ignored) {}
        try { if (ndjsonWriter != null) ndjsonWriter.close(); } catch (Exception ignored) {}
        ndjsonWriter = null;
    }
    private void startRecording() {
        if (recState != RecState.IDLE) return;

        try {
            openNdjson();
            imuSamples = 0;
            lapCount = 0;
            pausedTotalMonoNs = 0L;
            pausedStartMonoNs = 0L;

            startAsForeground("Recording");
            recState = RecState.RECORDING;

        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            safeCloseWriter();
            recState = RecState.IDLE;
            stopForeground(true);
            stopSelf();
        }
    }

    private void addLap() {
        if (recState != RecState.RECORDING) return;
        try {
            long mono = nowMonoNs();
            long utc = utcFromMono(mono);
            lapCount++;

            JSONObject lap = new JSONObject();
            lap.put("type", "lap");
            lap.put("lap", lapCount);
            lap.put("utc_ms", utc);
            lap.put("mono_ns", mono);
            writeLine(lap);
            ndjsonWriter.flush();
        } catch (Exception e) {
            Log.e(TAG, "addLap failed", e);
        }
    }

    private void togglePause() {
        if (recState == RecState.IDLE) return;

        try {
            long mono = nowMonoNs();
            long utc = utcFromMono(mono);

            if (recState == RecState.RECORDING) {
                recState = RecState.PAUSED;
                pausedStartMonoNs = mono;

                JSONObject ev = new JSONObject();
                ev.put("type", "event");
                ev.put("name", "recording_paused");
                ev.put("utc_ms", utc);
                ev.put("mono_ns", mono);
                ev.put("reason", "user");
                writeLine(ev);
                ndjsonWriter.flush();
                updateForeground("Paused");

            } else if (recState == RecState.PAUSED) {
                recState = RecState.RECORDING;
                if (pausedStartMonoNs != 0L) {
                    pausedTotalMonoNs += (mono - pausedStartMonoNs);
                    pausedStartMonoNs = 0L;
                }

                JSONObject ev = new JSONObject();
                ev.put("type", "event");
                ev.put("name", "recording_resumed");
                ev.put("utc_ms", utc);
                ev.put("mono_ns", mono);
                ev.put("reason", "user");
                writeLine(ev);
                ndjsonWriter.flush();
                updateForeground("Recording");
            }
        } catch (Exception e) {
            Log.e(TAG, "togglePause failed", e);
        }
    }

    private void stopRecording() {
        if (recState == RecState.IDLE) {
            stopSelf();
            return;
        }

        try {
            long mono = nowMonoNs();
            long utc = utcFromMono(mono);

            if (recState == RecState.PAUSED && pausedStartMonoNs != 0L) {
                pausedTotalMonoNs += (mono - pausedStartMonoNs);
                pausedStartMonoNs = 0L;
            }

            JSONObject ev = new JSONObject();
            ev.put("type", "event");
            ev.put("name", "recording_stopped");
            ev.put("utc_ms", utc);
            ev.put("mono_ns", mono);
            writeLine(ev);

            double durationS = (mono - startMonoNs) / 1e9;
            double pausedS = pausedTotalMonoNs / 1e9;

            JSONObject sum = new JSONObject();
            sum.put("type", "summary");
            sum.put("imu_samples", imuSamples);
            sum.put("laps", lapCount);
            sum.put("duration_s", durationS);
            sum.put("paused_s", pausedS);
            writeLine(sum);

            ndjsonWriter.flush();

        } catch (Exception e) {
            Log.e(TAG, "stopRecording failed", e);
        } finally {
            safeCloseWriter();
            recState = RecState.IDLE;
            stopForeground(true);
            stopSelf();
        }
    }
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device - try to reconnect
        if (address.equals(mBluetoothDeviceAddress) && mBluetoothGatt != null) {
            Log.d(TAG, "Trying to use an existing mBluetoothGatt for connection.");
            boolean result = mBluetoothGatt.connect();
            return result;
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect
        // parameter to false.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        return true;
    }

    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager =
                    (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(
                BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");

                broadcastUpdate(Event.GATT_CONNECTED);
                // attempt to discover services
                mBluetoothGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected from GATT server.");

                broadcastUpdate(Event.GATT_DISCONNECTED);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {

            if (status == BluetoothGatt.GATT_SUCCESS) {

                broadcastUpdate(Event.GATT_SERVICES_DISCOVERED);
                logServices(gatt); // debug

                // get the heart rate service
                movesenseService = gatt.getService(MOVESENSE_2_0_SERVICE);

                if (movesenseService != null) {
                    broadcastUpdate(Event.MOVESENSE_SERVICE_DISCOVERED);
                    logCharacteristics(movesenseService); // debug

                    BluetoothGattCharacteristic commandChar =
                            movesenseService.getCharacteristic(
                                    MOVESENSE_2_0_COMMAND_CHARACTERISTIC);
                    // command example: 1, 99, "/Meas/Acc/13"

                    /*
                    byte[] unsubscribeCommand = new byte[2];
                    unsubscribeCommand[0] = 2;
                    unsubscribeCommand[1] = 99;
                    commandChar.setValue(unsubscribeCommand);
                     */

                    byte[] command =
                            DataUtils.stringToAsciiArray(REQUEST_ID, IMU_COMMAND);
                    commandChar.setValue(command);
                    boolean wasSuccess = mBluetoothGatt.writeCharacteristic(commandChar);
                    Log.i(TAG, "commandChar Subscribe: "+ Arrays.toString(command));
                    Log.i(TAG, "subscribe success = " + wasSuccess);

                } else {
                    broadcastUpdate(Event.MOVESENSE_SERVICE_NOT_AVAILABLE);
                    Log.i(TAG, "movesense service not available");
                }
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic
                characteristic, int status) {
            Log.i(TAG, "onCharacteristicWrite " + characteristic.getUuid().toString());

            // First: Enable receiving notifications on the client side, i.e. on this Android.
            BluetoothGattService movesenseService = gatt.getService(MOVESENSE_2_0_SERVICE);
            BluetoothGattCharacteristic dataCharacteristic =
                    movesenseService.getCharacteristic(MOVESENSE_2_0_DATA_CHARACTERISTIC);
            boolean success = gatt.setCharacteristicNotification(dataCharacteristic, true);
            if (success) {
                broadcastUpdate(Event.MOVESENSE_SERVICE_DISCOVERED);
                Log.i(TAG, "setCharactNotification success");
                // Second: set enable notification server side (sensor).
                BluetoothGattDescriptor descriptor =
                        dataCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor); // callback: onDescriptorWrite
            } else {
                broadcastUpdate(Event.MOVESENSE_SERVICE_NOT_AVAILABLE);
                Log.i(TAG, "setCharacteristicNotification failed");
            }
        }

        @Override
        public void onDescriptorWrite(final BluetoothGatt gatt, BluetoothGattDescriptor
                descriptor, int status) {
            Log.i(TAG, "onDescriptorWrite, status " + status);

            if (CLIENT_CHARACTERISTIC_CONFIG.equals(descriptor.getUuid()))
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    // if success, we should receive data in onCharacteristicChanged
                    Log.i(TAG, "notifications enabled" + status);
                    broadcastUpdate(Event.MOVESENSE_NOTIFICATIONS_ENABLED);
                }
        }


        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            if (MOVESENSE_2_0_DATA_CHARACTERISTIC.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                if (data[0] == MOVESENSE_RESPONSE && data[1] == REQUEST_ID) {

                    ArrayList<DataPoint> dataPointList = DataUtils.IMU6DataConverter(data);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        if (Objects.isNull(dataPointList)) {
                            return;
                        }
                    }

                    // BoardLog: write if recording (and not paused)
                    if (recState == RecState.RECORDING && ndjsonWriter != null) {
                        try {
                            long mono = nowMonoNs(); // packet arrival time
                            for (DataPoint dp : dataPointList) {
                                JSONObject obj = new JSONObject();
                                obj.put("type", "imu");
                                obj.put("mono_ns", mono);
                                obj.put("t", dp.getTime()); // keep sensor time for later refinement

                                JSONArray acc = new JSONArray();
                                acc.put(dp.getAccX()).put(dp.getAccY()).put(dp.getAccZ());
                                obj.put("acc", acc);

                                JSONArray gyro = new JSONArray();
                                gyro.put(dp.getGyroX()).put(dp.getGyroY()).put(dp.getGyroZ());
                                obj.put("gyro", gyro);

                                writeLine(obj);
                                imuSamples++;
                            }

                            if (imuSamples % 52 == 0) ndjsonWriter.flush();

                        } catch (Exception e) {
                            Log.e(TAG, "NDJSON write failed", e);
                        }
                    }

                    // broadcast data update
                    broadcastMovesenseUpdate(dataPointList);
                }
            }
        }
    };

    //Broadcast methods for events
    private void broadcastUpdate(final Event event) {
        final Intent intent = new Intent(ACTION_GATT_MOVESENSE_EVENTS);
        intent.putExtra(EVENT, event);
        sendBroadcast(intent);
    }

    //Broadcast methods for data
    private void broadcastMovesenseUpdate(final ArrayList<DataPoint> dataPointList) {
        final Intent intent = new Intent(ACTION_GATT_MOVESENSE_EVENTS);
        intent.putExtra(EVENT, Event.DATA_AVAILABLE);
        intent.putParcelableArrayListExtra(MOVESENSE_DATA, dataPointList);
        sendBroadcast(intent);
    }

    //Android Service specific code for binding and unbinding to this Android service
    public class LocalBinder extends Binder {
        public BleIMUService getService() {

            return BleIMUService.this;
        }
    }

    private final IBinder mBinder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
            // Do not close BLE just because UI unbound; service may still be recording.
            return super.onUnbind(intent);
        }
        @Override
        public void onDestroy() {
            super.onDestroy();
            safeCloseWriter();
            close();
        }
    //logging and debugging
    private void logServices(BluetoothGatt gatt) {
        List<BluetoothGattService> services = gatt.getServices();
        for (BluetoothGattService service : services) {
            String uuid = service.getUuid().toString();
            Log.i(TAG, "service: " + uuid);
        }
    }

    //logging and debugging
    private void logCharacteristics(BluetoothGattService gattService) {
        List<BluetoothGattCharacteristic> characteristics =
                gattService.getCharacteristics();
        for (BluetoothGattCharacteristic chara : characteristics) {
            String uuid = chara.getUuid().toString();
            Log.i(TAG, "characteristic: " + uuid);
        }
    }
}


