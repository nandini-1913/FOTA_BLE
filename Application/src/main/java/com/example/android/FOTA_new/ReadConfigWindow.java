package com.example.android.FOTA_new;

import android.Manifest;
import android.app.AlertDialog;
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
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ReadConfigWindow extends AppCompatActivity {

    private TextView read_logtv;
    private  Button save_btn;
    private static final int PERMISSION_REQUEST_CODE = 100;

    private ImageButton Clear, Copy;
    private final static String TAG = ReadConfigWindow.class.getSimpleName();
    private BluetoothGattCharacteristic mwriteCharacteristic;
    private BluetoothLeService mBluetoothLeService;

    private String mDeviceAddress;
    public String mDeviceName;
    private TextView mConnectionState;

    private boolean mConnected = false;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

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
                read_logtv.append("Res:" + data + "\n");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.readconfigwindow);
        Button ble_name_set_btn = (Button) findViewById(R.id.ble_name_config_btn);
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

        read_logtv = (TextView) findViewById(R.id.read_logcat);
        save_btn = (Button) findViewById(R.id.save_excel);

        Clear = (ImageButton) findViewById(R.id.clear_btn);
        Copy = (ImageButton) findViewById(R.id.copy_btn);

        //save and export data in excel
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted, request it
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
        }

        save_btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveExcel(read_logtv.getText().toString());

            }
        });



        //clear $ copy code in log window
        Clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                read_logtv.setText("");
                Toast.makeText(ReadConfigWindow.this, "clear", Toast.LENGTH_SHORT).show();
            }
        });

        Copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("copy", read_logtv.getText().toString());
                clipboard
                        .setPrimaryClip(clip);
                Toast.makeText(ReadConfigWindow.this, "copied", Toast.LENGTH_SHORT).show();
            }
        });

        mConnectionState = (TextView) findViewById(R.id.connection_state);
        read_logtv.setMovementMethod(new ScrollingMovementMethod());
        read_logtv.append("WELCOME TO VOLTA DEBUG SCREEN");

    }

    public void read(View view) throws Exception {
        read_logtv.append("\r\nMETER STATUS READ REQUEST SENT");

        byte[] newPlaintext = new byte[4];
        newPlaintext[0] = 0x55;
        newPlaintext[1] = 0x15;
        newPlaintext[2] = 0x00;
        newPlaintext[3] = (byte) 0xAA;

        mwriteCharacteristic.setValue(newPlaintext);
        mBluetoothLeService.writeCharacteristic(mwriteCharacteristic);


    }


    private void saveExcel(String logData) {
        // Check if logData is empty
        if (logData.isEmpty()) {
            read_logtv.append("\nNo data to export.");
            return;
        }

        // Split the logData into lines
        String[] lines = logData.split("\n");

        // Create or open an existing workbook
        String fileName = "VOLTA_Meter_Log.xlsx";
        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), fileName);
        XSSFWorkbook workbook;
        XSSFSheet sheet;

        try {
            if (file.exists()) {
                // Open existing workbook if it exists
                FileInputStream fileIn = new FileInputStream(file);
                workbook = new XSSFWorkbook(fileIn);
                sheet = workbook.getSheetAt(0);
                fileIn.close();
            } else {
                // Create a new workbook and sheet if it does not exist
                workbook = new XSSFWorkbook();
                sheet = workbook.createSheet("Log Data");

                // Create the header row with the specified fields
                XSSFRow headerRow = sheet.createRow(0);
                String[] headers = {
                        "Sr. No.","SERIAL NO", "Total Gas Volume", "Firmware Version",
                        "RTC", "IP", "PORT", "APN", "uplink",
                        "MAGENT", "TILT", "Super flow", "SUPER_COUNT"
                };
                for (int i = 0; i < headers.length; i++) {
                    headerRow.createCell(i).setCellValue(headers[i]);
                }
            }

            // Create a map to hold the required data
            Map<String, String> dataMap = new LinkedHashMap<>();
            String[] requiredFields = {
                    "SERIAL NO", "Total Gas Volume", "Firmware Version",
                    "RTC", "IP", "PORT", "APN", "uplink",
                    "MAGENT", "TILT", "Super flow", "SUPER_COUNT"
            };

            // Variable to keep track of the serial number for each row
            int serialNumber = 1;

            // Iterate over each line to extract key-value pairs
            for (String line : lines) {
                line = line.trim();

                // Skip lines that do not contain a colon or are not in the required format
                if (line.isEmpty() || !line.contains(":")) continue;

                int colonIndex = line.indexOf(':');
                if (colonIndex != -1) {
                    String key = line.substring(0, colonIndex).trim();
                    String value = line.substring(colonIndex + 1).trim();

                    // Check if the key is one of the required fields
                    for (String field : requiredFields) {
                        if (key.equalsIgnoreCase(field)) {
                            dataMap.put(field, value);
                            break;
                        }
                    }
                }
            }

            // Only add rows if the required data is found
            if (!dataMap.isEmpty()) {
                // Create a new row in the Excel sheet
                int rowIndex = sheet.getLastRowNum() + 1;
                XSSFRow row = sheet.createRow(rowIndex);

                // Add serial number to the first column
                row.createCell(0).setCellValue(serialNumber++);

                // Populate the row with the extracted data
                int cellIndex = 1;
                for (String field : requiredFields) {
                    String value = dataMap.getOrDefault(field, "");
                    row.createCell(cellIndex++).setCellValue(value);
                }

                // Save the data to the Excel file
                FileOutputStream fileOut = new FileOutputStream(file);
                workbook.write(fileOut);
                workbook.close();
                fileOut.close();

                read_logtv.append("\nExcel file updated at: " + file.getAbsolutePath());
            } else {
                read_logtv.append("\nNo valid data to export.");
            }

        } catch (IOException e) {
            read_logtv.append("\nError while saving Excel file: " + e.getMessage());
        }
    }

    public void ble_name_config(View view) throws Exception {
        new AlertDialog.Builder(this)
                .setTitle("Permission Required")
                .setMessage("Do you want to set the Ble Device name?")
                .setPositiveButton("Yes", (dialog, which) ->{

                    try {

//                        logTv.append("\r\nBLE NAME CONFIG REQUEST SENT");
                        byte[] newPlaintext = new byte[4];
                        newPlaintext[0] = 0x55;
                        newPlaintext[1] = 0x10;
                        newPlaintext[2] = 0x00;
                        newPlaintext[3] = (byte) 0xAA;
                        mwriteCharacteristic.setValue(newPlaintext);
                        mBluetoothLeService.writeCharacteristic(mwriteCharacteristic);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                })
                .setNegativeButton("No", (dialog, which) -> dialog.dismiss())
                .show();


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

    public void exitApp(View view) {
        System.exit(0);
    }


}
