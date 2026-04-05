package com.example.movesensedatarecorder;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;


import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
import static com.example.movesensedatarecorder.service.UUIDs.MOVESENSE;
import static com.example.movesensedatarecorder.service.UUIDs.MOVESENSE_2_0_SERVICE;
import static com.example.movesensedatarecorder.utils.MsgUtils.showToast;

import com.example.movesensedatarecorder.adapters.BTDeviceAdapter;
import com.example.movesensedatarecorder.utils.MsgUtils;
import com.example.movesensedatarecorder.utils.PermissionUtils;

public class ScanActivity extends AppCompatActivity {

    private static final String TAG = ScanActivity.class.getSimpleName();

    private BluetoothAdapter mBluetoothAdapter;
    private boolean mScanning;
    private Handler mHandler;

    private ArrayList<BluetoothDevice> mDeviceList;
    private BTDeviceAdapter mAdapter;
    private TextView mScanInfoView;

    private static final List<ScanFilter> IMU_SCAN_FILTER;
    private static final ScanSettings SCAN_SETTINGS;

    private String[] PERMISSIONS = {
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_ADMIN
    };
    private PermissionUtils permissionUtils;

    static {
        ScanFilter scanFilter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(MOVESENSE_2_0_SERVICE)) //service UUID
                .build();
        IMU_SCAN_FILTER = new ArrayList<>();
        IMU_SCAN_FILTER.add(scanFilter);
        SCAN_SETTINGS = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
    }

    private static final long SCAN_PERIOD = 5000; // milli seconds

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan);

        mHandler = new Handler();

        mScanInfoView = findViewById(R.id.scan_info);
        Button startScanButton = findViewById(R.id.button_start_scan);
        ListView scanListView = findViewById(R.id.scan_list_view);

        BluetoothAdapter defaultAdapter = BluetoothAdapter.getDefaultAdapter();
        if (defaultAdapter == null) {
            MsgUtils.showToast(getApplicationContext(), "Bluetooth not supported on this phone");
            finish();
            return;
        }
        mBluetoothAdapter = defaultAdapter;

        mDeviceList = new ArrayList<>();
        mAdapter = new BTDeviceAdapter(this, mDeviceList);
        scanListView.setAdapter(mAdapter);
        scanListView.setOnItemClickListener((arg0, arg1, position, arg3) -> onDeviceSelected(position));

        startScanButton.setOnClickListener(v -> {
            MsgUtils.showToast(getApplicationContext(), "Scanning...");
            mDeviceList.clear();
            mAdapter.notifyDataSetChanged();
            startScanning(IMU_SCAN_FILTER, SCAN_SETTINGS, SCAN_PERIOD);
        });
    }
    @Override
    protected void onStart() {
        super.onStart();
        if (mScanInfoView != null) {
            mScanInfoView.setText(R.string.no_devices_found);
        }
        initBLE();
    }
    @Override
    protected void onStop() {
        super.onStop();
        stopScanning();
        if (mDeviceList != null) {
            mDeviceList.clear();
        }
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }

    private void startScanning(
            List<ScanFilter> scanFilters,
            ScanSettings scanSettings,
            long scanPeriod) {

        if (mBluetoothAdapter == null) {
            MsgUtils.showToast(getApplicationContext(), "Bluetooth adapter not ready");
            return;
        }

        final BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            MsgUtils.showToast(getApplicationContext(), "Bluetooth scanner not available");
            return;
        }

        if (!mScanning) {
            mHandler.postDelayed(() -> {
                if (mScanning) {
                    mScanning = false;
                    scanner.stopScan(mScanCallback);
                }
            }, scanPeriod);

            mScanning = true;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {
                    MsgUtils.showToast(getApplicationContext(), "Enable bluetooth permission");
                    return;
                }
            }

            scanner.startScan(mScanCallback);
            Log.i(TAG, "SCAN STARTED");
            mScanInfoView.setText("Scanning...");
        }
    }
    private void stopScanning() {
        if (mScanning) {
            BluetoothLeScanner scanner =
                    mBluetoothAdapter.getBluetoothLeScanner();
            scanner.stopScan(mScanCallback);
            mScanning = false;
            //showToast(getApplicationContext(),"BLE scan stopped");
        }
    }

    //Callback methods for the BluetoothLeScanner
    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            Log.i(TAG, "onScanResult");
            final BluetoothDevice device = result.getDevice();
            final String name = device.getName();

            mHandler.post(() -> {
                if (name != null
                        && name.contains(MOVESENSE)
                        && !mDeviceList.contains(device)) {
                    mDeviceList.add(device);
                    mAdapter.notifyDataSetChanged();
                    String info = "Found " + mDeviceList.size() + " device(s)\n"
                            + "Touch to connect";
                    mScanInfoView.setText(info);
                }
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.i(TAG, "onScanFailed");
        }
    };

    private void onDeviceSelected(int position) {
        BluetoothDevice selectedDevice = mDeviceList.get(position);
        Intent intent = new Intent(ScanActivity.this, DataActivity.class);
        intent.putExtra(DataActivity.EXTRAS_DEVICE_NAME, selectedDevice.getName());
        intent.putExtra(DataActivity.EXTRAS_DEVICE_ADDRESS, selectedDevice.getAddress());
        stopScanning();
        startActivity(intent);
    }

    //requests for user permissions to access and turn on Bluetooth.
    public static final int REQUEST_ENABLE_BT = 1000;
    public static final int REQUEST_ACCESS_LOCATION = 1001;

    private void initBLE() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            showToast(  getApplicationContext(), "BLE not supported");
            finish();
        } else {
            permissionUtils = new PermissionUtils(this, PERMISSIONS);
            if(permissionUtils.arePermissionsEnabled()){
                Log.d(TAG, "Permission granted 1");
            } else {
                permissionUtils.requestMultiplePermissions();
            }
        }

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // turn on BT, i.e. start an activity for the user consent
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
    }

    // callback for ActivityCompat.requestPermissions
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(permissionUtils.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            Log.d(TAG, "Permission granted 2");
        }
    }

    // callback for request to turn on BT
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // if user chooses not to enable Bluetooth.
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_CANCELED) {
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

}