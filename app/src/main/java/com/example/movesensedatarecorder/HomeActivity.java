package com.example.movesensedatarecorder;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;

import com.example.movesensedatarecorder.model.Subject;
import com.example.movesensedatarecorder.utils.MsgUtils;
import com.example.movesensedatarecorder.utils.SavingUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import androidx.appcompat.app.AppCompatActivity;

public class HomeActivity extends AppCompatActivity {

    private static final String TAG = AddSubjActivity.class.getSimpleName();
    private Button buttonSubj, buttonExp, buttonExport;
    private List<Subject> subjSet = new ArrayList<>();
    private String FILE_NAME = "subjects_data";
    private String CSV_FILE_NAME = "subjects_data.csv";
    private static final int CREATE_FILE = 1;
    private String content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        content = null;

        buttonSubj = findViewById(R.id.button_subj);
        buttonSubj.setOnClickListener(v -> {
            MsgUtils.showToast(getApplicationContext(), "Boards screen not enabled yet");
        });

        buttonExp = findViewById(R.id.button_exp);
        buttonExp.setOnClickListener(v -> {
            startActivity(new Intent(getApplicationContext(), ScanActivity.class));
        });

        buttonExport = findViewById(R.id.button_export);
        buttonExport.setOnClickListener(v -> {
            MsgUtils.showToast(getApplicationContext(), "Export not enabled yet in BoardLog");
        });
    }

    private void saveToCSV() {
        try {
            //save to internal storage
            saveToInternalStorage();
            //save to external shared storage
            saveToExternalStorage();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveToInternalStorage() throws IOException {
        File file = new File(getApplicationContext().getFilesDir(), CSV_FILE_NAME);
        Log.i(TAG, file.getPath());
        if (file.exists())
            file.delete();
        file.createNewFile();
        FileWriter fw = new FileWriter(file, true);
        BufferedWriter bw = new BufferedWriter(fw);
        bw.write(content);
        bw.flush();
        bw.close();
    }

    private void saveToExternalStorage() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, CSV_FILE_NAME);

        startActivityForResult(intent, CREATE_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == CREATE_FILE) {
            OutputStream fileOutputStream = null;
            try {
                fileOutputStream = getContentResolver().openOutputStream(data.getData());
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            try {
                assert fileOutputStream != null;
                fileOutputStream.write(content.getBytes()); //Write the obtained string to csv
                fileOutputStream.flush();
                fileOutputStream.close();
                MsgUtils.showToast(getApplicationContext(), "file saved!");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private String recordAsCsv() {
        //https://stackoverflow.com/questions/35057456/how-to-write-arraylistobject-to-a-csv-file
        String recordAsCsv = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            recordAsCsv = subjSet.stream()
                    .map(Subject::toCsvRow)
                    .collect(Collectors.joining(System.getProperty("line.separator")));
        }
        return recordAsCsv;
    }

    private void exportData() throws IOException, ClassNotFoundException {
        if (subjSet.isEmpty()) {
            boolean fileExist = fileExist(FILE_NAME);
            if (fileExist) {
                File oldfile = new File(getApplicationContext().getFilesDir(), FILE_NAME);
                subjSet = SavingUtils.readSubjectFile(FILE_NAME, oldfile);
            } else {
                MsgUtils.showToast(getApplicationContext(), "no saved subjects");
                return;
            }
        }
        try {
            String heading = "name,lastName,email,height,weight,subjID";
            content = heading + "\n" + recordAsCsv();
            saveToCSV();
        } catch (Exception e) {
            e.printStackTrace();
            MsgUtils.showToast(getApplicationContext(), "unable to export list");
        }
    }

    private boolean fileExist(String fname) {
        File file = getBaseContext().getFileStreamPath(fname);
        return file.exists();
    }
}
