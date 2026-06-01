package com.example.android.FOTA_new;

import android.Manifest;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Date;
import java.text.SimpleDateFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.apache.poi.ss.usermodel.CellStyle;

public class DebugWindow extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener {

    private Workbook workbook;
    private SimpleDateFormat dateFormat;
    private String currentDeviceName;
    private static final int PERMISSION_REQUEST_CODE = 100;
    Button exit_btn, send_cmd_btn;
    EditText editText;

    File filePath;

    private  ImageButton download;
    private  TextView debug_logtv;
    private  Button save_btn, save_event;
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private int dayCounter = 1;

    private ImageButton Clear, Copy;
    private Switch nb_en_input;
    byte nb_en_flag = 0;
    private final static String TAG = DebugWindow.class.getSimpleName();
    private BluetoothGattCharacteristic mwriteCharacteristic;
    private BluetoothLeService mBluetoothLeService;
    //int connection_state = 0;
    private String mDeviceAddress;
    public String mDeviceName;
    private TextView mConnectionState;
    byte[] data_after_encrypt = new byte[48];

    private boolean mConnected = false;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    byte key[] = {0x61, 0x21, 0x31, 0x41, 0x51, 0x61, 0x71, (byte) 0x81, 0x12, 0x22, 0x32, 0x42, 0x52, 0x62, 0x72, (byte) 0x82};

    byte plaintext[] = new byte[72];

    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                Toast.makeText(getApplicationContext(), "Unable to initialize Bluetooth", Toast.LENGTH_SHORT).show();
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            Log.d(TAG, "Initialized Bluetooth");
            mBluetoothLeService.disconnect();
            mBluetoothLeService.connect(mDeviceAddress);
            Log.e(TAG, "DEVICE ADDRESS" + mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
            Log.d(TAG, "Initialized Bluetooth failed");
        }
    };

    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                updateConnectionState(R.string.connected);
                invalidateOptionsMenu();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState(R.string.disconnected);
                invalidateOptionsMenu();
                // clearUI(); // Uncomment if you need to clear the UI
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                displayGattServices(mBluetoothLeService.getSupportedGattServices());

                if (mGattCharacteristics != null && !mGattCharacteristics.isEmpty()) {
                    final BluetoothGattCharacteristic characteristic = mGattCharacteristics.get(3).get(0);

                    final int charaProp = characteristic.getProperties();
                    if ((charaProp & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        mBluetoothLeService.setCharacteristicNotification(characteristic, true);

                        // Enable notifications by writing to the CCC descriptor
                        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            mBluetoothLeService.writeDescriptor(descriptor);
                        }
                    }

                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                        mwriteCharacteristic = characteristic;
                    }
                }
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                String data = intent.getStringExtra(BluetoothLeService.EXTRA_DATA);
                debug_logtv.append("Res:" + data + "\n");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.debug_window);
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra("device_address")) {
            mDeviceAddress = intent.getStringExtra("device_address");
        }
        if (intent != null && intent.hasExtra("device_name")) {
            mDeviceName = intent.getStringExtra("device_name");
        }

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(mDeviceName);
        } else {
            Log.e(TAG, "ActionBar is not available");
        }

        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);

        send_cmd_btn = (Button) findViewById(R.id.debug_write_btn);
        debug_logtv = (TextView) findViewById(R.id.debug_logcat);
        save_btn = (Button) findViewById(R.id.daily_log);
        save_event = (Button) findViewById(R.id.tamper_log);
        Clear = (ImageButton) findViewById(R.id.clear_btn);
        Copy = (ImageButton) findViewById(R.id.copy_btn);
        nb_en_input = (Switch) findViewById(R.id.nb_switch);
        editText = findViewById(R.id.at_com);
        Spinner spinner = findViewById(R.id.spinner);
        nb_en_flag = 0;
        nb_en_input.setChecked(false);
        nb_en_input.setOnCheckedChangeListener(this);

        //save and export data in excel
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted, request it
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
        }

        save_btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportToExcel(debug_logtv.getText().toString());

            }
        });

        save_event.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportEventExcel(debug_logtv.getText().toString());

            }
        });


        //clear $ copy code in log window
        Clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                debug_logtv.setText("");
                Toast.makeText(DebugWindow.this, "clear", Toast.LENGTH_SHORT).show();
            }
        });

        Copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("copy", debug_logtv.getText().toString());
                clipboard
                        .setPrimaryClip(clip);
                Toast.makeText(DebugWindow.this, "copied", Toast.LENGTH_SHORT).show();
            }
        });

        mConnectionState = (TextView) findViewById(R.id.connection_state);

        debug_logtv.setMovementMethod(new ScrollingMovementMethod());
        debug_logtv.append("WELCOME TO VOLTA DEBUG SCREEN");

//DROP DOWN LIST FOR AT COMMANDS SEQUENCE............
        String[] options = {"ATE0", " AT+CPIN?", "AT+CSQ" ," AT+CEREG?" , " AT+COPS?" ,"AT+IVSN","AT+CGMR", "AT+CGDCONT?"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        spinner.setAdapter(adapter);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                // Perform action based on selected item
                String selectedOption = options[position];

                editText.setText(selectedOption);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                // Do nothing
            }
        });

    }

  // DATA EXPORTING INTO EXCEL ,EEPROM DAILY LOGS UPTO 180 DAYS
  private void exportToExcel(String data) {
      Workbook workbook = null;
      Sheet sheet = null;
      SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

      try {
          // Generate a unique file name based on mDeviceName and timestamp
          SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
          String fileName = mDeviceName.replaceAll("[^a-zA-Z0-9-_\\.]", "_") + " daily_log" + ".xlsx"; // Use a consistent file name

          File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName);

          if (file.exists()) {
              // If file exists, load the existing workbook
              FileInputStream fis = new FileInputStream(file);
              workbook = new XSSFWorkbook(fis);
              fis.close();
          } else {
              // If file does not exist, create new workbook
              workbook = new XSSFWorkbook();
          }

          // Get or create the sheet
          sheet = workbook.getSheet("Data");
          if (sheet == null) {
              sheet = workbook.createSheet("Data");

              // Create headers if sheet is newly created
              Row headerRow = sheet.createRow(0);
              headerRow.createCell(0).setCellValue("Sr No.");
              headerRow.createCell(1).setCellValue("Days");
              headerRow.createCell(2).setCellValue("Meter sr. no.");
              headerRow.createCell(3).setCellValue("Meter Type");
              headerRow.createCell(4).setCellValue("Meter mfg.");
              headerRow.createCell(5).setCellValue("Encrypted Data");
              headerRow.createCell(6).setCellValue("Retrieving Time stamp");

              // Set column widths for headers
              sheet.setColumnWidth(0, 1000);
              sheet.setColumnWidth(1, 1000);
              sheet.setColumnWidth(2, 4000);
              sheet.setColumnWidth(3, 3000);
              sheet.setColumnWidth(4, 3000);
              sheet.setColumnWidth(5, 10000);
              sheet.setColumnWidth(6, 5000);
          } else {
              // Clear existing data if sheet already exists
              clearSheet(sheet);
          }

          // Find the next available row after headers
          int nextRowNum = sheet.getLastRowNum() + 1;

          // Split the data into lines and write each line to a row in the Excel sheet
          String[] lines = data.split("\n");

          for (String line : lines) {
              int dataIndex = line.indexOf("EEPROM DATA:");
              while (dataIndex != -1) {
                  Row row = sheet.createRow(nextRowNum);

                  Cell serialCell = row.createCell(0);
                  serialCell.setCellValue(nextRowNum);

                  Cell daysCell = row.createCell(1);
                  daysCell.setCellValue(dayCounter);
                  dayCounter++;

                  Cell meterSrNoCell = row.createCell(2);
                  meterSrNoCell.setCellValue(mDeviceName);

                  Cell meterTypeCell = row.createCell(3);
                  meterTypeCell.setCellValue("NBIoT");

                  Cell meterMfgCell = row.createCell(4);
                  meterMfgCell.setCellValue("CIPL");

                  String encryptedData = extractEncryptedData(line.substring(dataIndex));
                  Cell encryptedDataCell = row.createCell(5);
                  encryptedDataCell.setCellValue(encryptedData);

                  Cell retrievingTimeStampCell = row.createCell(6);
                  String timeStamp = dateFormat.format(new Date());
                  retrievingTimeStampCell.setCellValue(timeStamp);

                  CellStyle wrapCellStyle = workbook.createCellStyle();
                  wrapCellStyle.setWrapText(true);
                  encryptedDataCell.setCellStyle(wrapCellStyle);

                  nextRowNum++;

                  dataIndex = line.indexOf("EEPROM DATA:", dataIndex + 1);
              }
          }

          // Save workbook to file
          FileOutputStream fileOut = new FileOutputStream(file);
          workbook.write(fileOut);
          fileOut.close();

          // Notify user of success
          Toast.makeText(this, "Excel file exported successfully", Toast.LENGTH_SHORT).show();
          Log.d("DebugWindow", "Excel file exported: " + file.getAbsolutePath());

      } catch (IOException e) {
          e.printStackTrace();
          Log.e("DebugWindow", "Error exporting Excel: " + e.getMessage());
          Toast.makeText(this, "Error exporting Excel: " + e.getMessage(), Toast.LENGTH_SHORT).show();
      } finally {
          if (workbook != null) {
              try {
                  workbook.close();
              } catch (IOException e) {
                  e.printStackTrace();
              }
          }
      }
  }

    // Helper method to clear the contents of a sheet
    private void clearSheet(Sheet sheet) {
        int lastRowNum = sheet.getLastRowNum();

        for (int i = lastRowNum; i >= 1; i--) { // Start from 1 to preserve headers
            sheet.removeRow(sheet.getRow(i));
        }
    }


    // Helper method to extract 16 bytes of data after the colon (:)
    private String extractEncryptedData(String line) {
        String result = "";

        // Find the index of "EEPROM DATA:"
        int dataIndex = line.indexOf("EEPROM DATA:");
        if (dataIndex != -1) {
            // Move index to the start of data after "EEPROM DATA:"
            dataIndex += "EEPROM DATA:".length();

            // Extract substring from dataIndex to dataIndex + 16 characters or end of line
            if (dataIndex + 32 <= line.length()) {
                result = line.substring(dataIndex, dataIndex + 32);
            } else {
                result = line.substring(dataIndex); // If less than 16 characters remaining
            }
        }

        return result.trim(); // Trim any trailing whitespace
    }



    //EXPORT TAMPER EVENT INTO EXCEL SHEET
    private void exportEventExcel(String data) {
        Workbook workbook = null;
        Sheet sheet = null;
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        try {
            // Generate a unique file name based on mDeviceName and timestamp
            SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
            String fileName = mDeviceName.replaceAll("[^a-zA-Z0-9-_\\.]", "_") + " Events_log" + ".xlsx"; // Use a consistent file name

            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName);

            if (file.exists()) {
                // If file exists, load the existing workbook
                FileInputStream fis = new FileInputStream(file);
                workbook = new XSSFWorkbook(fis);
                fis.close();
            } else {
                // If file does not exist, create new workbook
                workbook = new XSSFWorkbook();
            }

            // Get or create the sheet
            sheet = workbook.getSheet("Data");
            if (sheet == null) {
                sheet = workbook.createSheet("Data");

                // Create headers if sheet is newly created
                Row headerRow = sheet.createRow(0);
                headerRow.createCell(0).setCellValue("Sr No.");
                headerRow.createCell(1).setCellValue("No. of Events");
                headerRow.createCell(2).setCellValue("Meter sr. no.");
                headerRow.createCell(3).setCellValue("Meter Type");
                headerRow.createCell(4).setCellValue("Meter mfg.");
                headerRow.createCell(5).setCellValue("Encrypted Data");
                headerRow.createCell(6).setCellValue("Retrieving Time stamp");

                // Set column widths for headers
                sheet.setColumnWidth(0, 1000);
                sheet.setColumnWidth(1, 1000);
                sheet.setColumnWidth(2, 4000);
                sheet.setColumnWidth(3, 3000);
                sheet.setColumnWidth(4, 3000);
                sheet.setColumnWidth(5, 10000);
                sheet.setColumnWidth(6, 5000);
            } else {
                // Clear existing data if sheet already exists
                clearSheet(sheet);
            }

            // Find the next available row after headers
            int nextRowNum = sheet.getLastRowNum() + 1;

            // Split the data into lines and write each line to a row in the Excel sheet
            String[] lines = data.split("\n");

            for (String line : lines) {
                int dataIndex = line.indexOf("TAMPER DATA:");
                while (dataIndex != -1) {
                    Row row = sheet.createRow(nextRowNum);

                    Cell serialCell = row.createCell(0);
                    serialCell.setCellValue(nextRowNum);

                    Cell daysCell = row.createCell(1);
                    daysCell.setCellValue(dayCounter);
                    dayCounter++;

                    Cell meterSrNoCell = row.createCell(2);
                    meterSrNoCell.setCellValue(mDeviceName);

                    Cell meterTypeCell = row.createCell(3);
                    meterTypeCell.setCellValue("NBIoT");

                    Cell meterMfgCell = row.createCell(4);
                    meterMfgCell.setCellValue("CIPL");

                    String encryptedData = extractEncryptedTamperData(line.substring(dataIndex));
                    Cell encryptedDataCell = row.createCell(5);
                    encryptedDataCell.setCellValue(encryptedData);

                    Cell retrievingTimeStampCell = row.createCell(6);
                    String timeStamp = dateFormat.format(new Date());
                    retrievingTimeStampCell.setCellValue(timeStamp);

                    CellStyle wrapCellStyle = workbook.createCellStyle();
                    wrapCellStyle.setWrapText(true);
                    encryptedDataCell.setCellStyle(wrapCellStyle);

                    nextRowNum++;

                    dataIndex = line.indexOf("TAMPER DATA:", dataIndex + 1);
                }
            }

            // Save workbook to file
            FileOutputStream fileOut = new FileOutputStream(file);
            workbook.write(fileOut);
            fileOut.close();

            // Notify user of success
            Toast.makeText(this, "Excel file exported successfully", Toast.LENGTH_SHORT).show();
            Log.d("DebugWindow", "Excel file exported: " + file.getAbsolutePath());

        } catch (IOException e) {
            e.printStackTrace();
            Log.e("DebugWindow", "Error exporting Excel: " + e.getMessage());
            Toast.makeText(this, "Error exporting Excel: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        } finally {
            if (workbook != null) {
                try {
                    workbook.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // Helper method to extract 16 bytes of data after the colon (:)
    private String extractEncryptedTamperData(String line) {
        String result = "";

        // Find the index of "EEPROM DATA:"
        int dataIndex = line.indexOf("TAMPER DATA:");
        if (dataIndex != -1) {
            // Move index to the start of data after "EEPROM DATA:"
            dataIndex += "TAMPER DATA:".length();

            // Extract substring from dataIndex to dataIndex + 16 characters or end of line
            if (dataIndex + 32 <= line.length()) {
                result = line.substring(dataIndex, dataIndex + 32);
            } else {
                result = line.substring(dataIndex); // If less than 16 characters remaining
            }
        }

        return result.trim(); // Trim any trailing whitespace
    }




    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter(), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        }
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_connect:
                mBluetoothLeService.connect(mDeviceAddress);
                return true;
            case R.id.menu_disconnect:
                mBluetoothLeService.disconnect();
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
            }
        });
    }

    private void displayGattServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        String uuid = null;
        String unknownServiceString = getResources().getString(R.string.unknown_service);
        String unknownCharaString = getResources().getString(R.string.unknown_characteristic);
        ArrayList<HashMap<String, String>> gattServiceData = new ArrayList<HashMap<String, String>>();
        ArrayList<ArrayList<HashMap<String, String>>> gattCharacteristicData
                = new ArrayList<ArrayList<HashMap<String, String>>>();
        mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            HashMap<String, String> currentServiceData = new HashMap<String, String>();
            uuid = gattService.getUuid().toString();
            gattServiceData.add(currentServiceData);

            ArrayList<HashMap<String, String>> gattCharacteristicGroupData =
                    new ArrayList<HashMap<String, String>>();
            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();
            ArrayList<BluetoothGattCharacteristic> charas =
                    new ArrayList<BluetoothGattCharacteristic>();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                charas.add(gattCharacteristic);
                HashMap<String, String> currentCharaData = new HashMap<String, String>();
                uuid = gattCharacteristic.getUuid().toString();
                gattCharacteristicGroupData.add(currentCharaData);
            }
            mGattCharacteristics.add(charas);
            gattCharacteristicData.add(gattCharacteristicGroupData);
        }
    }

    static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {

        switch (compoundButton.getId()) {
            case R.id.nb_switch:
                if (isChecked) {
                    nb_en_flag = 1;
                    plaintext[0] = 0x55;
                    plaintext[1] = 0x13;
                    plaintext[2] = 0x0F;
                    byte[] newPlaintext = new byte[3];

// Copy the existing plaintext data to the new array
                    System.arraycopy(plaintext, 0, newPlaintext, 0, 3);

// Copy the encrypted data to the new array, starting from the end of the existing plaintext data
                    plaintext = newPlaintext;
                    int newSize = plaintext.length + 1;

// Create a new array with the increased size
                    newPlaintext = new byte[newSize];

// Copy the existing plaintext data to the new array
                    System.arraycopy(plaintext, 0, newPlaintext, 0, plaintext.length);

// Add 0x12 at the end of the new array
                    newPlaintext[newPlaintext.length - 1] = (byte) 0xAA;

// Use the newPlaintext array going forward
                    plaintext = newPlaintext;
                    mwriteCharacteristic.setValue(plaintext);
                    mBluetoothLeService.writeCharacteristic(mwriteCharacteristic);
                } else {
                    nb_en_flag = 0;
                    plaintext[0] = 0x55;
                    plaintext[1] = 0x13;
                    plaintext[2] = 0x10;
                    byte[] newPlaintext = new byte[3];

// Copy the existing plaintext data to the new array
                    System.arraycopy(plaintext, 0, newPlaintext, 0, 3);

// Copy the encrypted data to the new array, starting from the end of the existing plaintext data
                    plaintext = newPlaintext;
                    int newSize = plaintext.length + 1;

// Create a new array with the increased size
                    newPlaintext = new byte[newSize];

// Copy the existing plaintext data to the new array
                    System.arraycopy(plaintext, 0, newPlaintext, 0, plaintext.length);

// Add 0x12 at the end of the new array
                    newPlaintext[newPlaintext.length - 1] = (byte) 0xAA;

// Use the newPlaintext array going forward
                    plaintext = newPlaintext;
                    mwriteCharacteristic.setValue(plaintext);
                    mBluetoothLeService.writeCharacteristic(mwriteCharacteristic);
                }
                break;

            default:
                throw new IllegalStateException("Unexpected value: " + compoundButton.getId());
        }
    }

    public void nb_cmd_config(View view) throws Exception {
        String at_command = editText.getText().toString();
        at_command += "\r\n";
        debug_logtv.append("\r\nsent cmd: " + at_command + "\r\n");
        byte[] ATCommandsChars = at_command.getBytes(StandardCharsets.UTF_8);
        int blockSize = 16;
        int bufferSize = ((ATCommandsChars.length + blockSize - 1) / blockSize) * blockSize;

        // Allocate the buffer
        byte[] data_encrypt = new byte[bufferSize];
        data_encrypt[0] = 0x55; // length of the data
        data_encrypt[1] = (byte) at_command.length(); // length of the data
        System.arraycopy(ATCommandsChars, 0, data_encrypt, 2, at_command.length());
        data_after_encrypt = encrypt(data_encrypt, key);
        encrypt_print(data_after_encrypt);
        plaintext[0] = 0x55;
        plaintext[1] = 0x13;
        plaintext[2] = 0x11;
        plaintext[3] = (byte) data_after_encrypt.length;
        int newSize = 4 + data_after_encrypt.length;
        byte[] newPlaintext = new byte[newSize];

// Copy the existing plaintext data to the new array
        System.arraycopy(plaintext, 0, newPlaintext, 0, 4);

// Copy the encrypted data to the new array, starting from the end of the existing plaintext data
        System.arraycopy(data_after_encrypt, 0, newPlaintext, 4, data_after_encrypt.length);

// Use the newPlaintext array going forward
        plaintext = newPlaintext;
        newSize = plaintext.length + 1;

// Create a new array with the increased size
        newPlaintext = new byte[newSize];

// Copy the existing plaintext data to the new array
        System.arraycopy(plaintext, 0, newPlaintext, 0, plaintext.length);

// Add 0x12 at the end of the new array
        newPlaintext[newPlaintext.length - 1] = (byte) 0xAA;

// Use the newPlaintext array going forward
        plaintext = newPlaintext;
        mwriteCharacteristic.setValue(plaintext);
        mBluetoothLeService.writeCharacteristic(mwriteCharacteristic);
    }

    private void encrypt_print(byte[] data_enc) {
        StringBuilder stringBuilder = new StringBuilder();
        for (byte b : data_enc) {
            stringBuilder.append(String.format("%02X ", b));
        }
        Log.d(TAG, "ENCRYPTED VALUE: " + stringBuilder.toString().trim());
    }

    public static byte[] encrypt(byte[] data, byte[] key) throws Exception {
        SecretKeySpec secretKeySpec = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS7Padding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec);
        return cipher.doFinal(data);
    }

    public void eeprom_log_read_config(View view) throws Exception {
        plaintext[0] = 0x55;
        plaintext[1] = 0x13;
        plaintext[2] = 0x05;
        byte[] newPlaintext = new byte[3];

// Copy the existing plaintext data to the new array
        System.arraycopy(plaintext, 0, newPlaintext, 0, 3);

// Copy the encrypted data to the new array, starting from the end of the existing plaintext data
        plaintext = newPlaintext;
        int newSize = plaintext.length + 1;

// Create a new array with the increased size
        newPlaintext = new byte[newSize];

// Copy the existing plaintext data to the new array
        System.arraycopy(plaintext, 0, newPlaintext, 0, plaintext.length);

// Add 0x12 at the end of the new array
        newPlaintext[newPlaintext.length - 1] = (byte) 0xAA;

// Use the newPlaintext array going forward
        plaintext = newPlaintext;
        mwriteCharacteristic.setValue(plaintext);
        mBluetoothLeService.writeCharacteristic(mwriteCharacteristic);
    }

    public void eeprom_event_read_config(View view) throws Exception {
        plaintext[0] = 0x55;
        plaintext[1] = 0x13;
        plaintext[2] = 0x06;
        byte[] newPlaintext = new byte[3];

// Copy the existing plaintext data to the new array
        System.arraycopy(plaintext, 0, newPlaintext, 0, 3);

// Copy the encrypted data to the new array, starting from the end of the existing plaintext data
        plaintext = newPlaintext;
        int newSize = plaintext.length + 1;

// Create a new array with the increased size
        newPlaintext = new byte[newSize];

// Copy the existing plaintext data to the new array
        System.arraycopy(plaintext, 0, newPlaintext, 0, plaintext.length);

// Add 0x12 at the end of the new array
        newPlaintext[newPlaintext.length - 1] = (byte) 0xAA;

// Use the newPlaintext array going forward
        plaintext = newPlaintext;
        mwriteCharacteristic.setValue(plaintext);
        mBluetoothLeService.writeCharacteristic(mwriteCharacteristic);
    }

    public void exitApp(View view) {
        System.exit(0);
    }


}