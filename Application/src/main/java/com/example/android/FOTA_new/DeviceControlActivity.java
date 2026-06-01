package com.example.android.FOTA_new;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
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
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;


import androidx.annotation.Nullable;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class DeviceControlActivity extends Activity implements CompoundButton.OnCheckedChangeListener {

    private int fotaPasswordAttempts = 0;
    private final int MAX_FOTA_ATTEMPTS = 3;


    byte plaintext[] = new byte[72];
    byte key[] = {0x61, 0x21, 0x31, 0x41, 0x51, 0x61, 0x71, (byte) 0x81, 0x12, 0x22, 0x32, 0x42, 0x52, 0x62, 0x72, (byte) 0x82};

    byte[] data_after_encrypt = new byte[48];

    private final static String TAG = DeviceControlActivity.class.getSimpleName();

    private ImageButton Clear, Copy;
    private TextView logTv;

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
    public String mDeviceName;
    private NumberPicker numberPicker;
    private TextView Edit_Text_view;
    private TextView mConnectionState;
    private String mDeviceAddress;
    private BluetoothAdapter mBluetoothAdapter;

    private BluetoothGatt mBluetoothGatt;
    private BluetoothLeService mBluetoothLeService;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    private boolean mConnected = false;
    //int connection_state=0;
    private BluetoothGattCharacteristic mNotifyCharacteristic;
    private BluetoothGattCharacteristic mwriteCharacteristic;



    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
//                Log.e(TAG, "Unable to initialize Bluetooth");
                Toast.makeText(getApplicationContext(), "Unable to initialize Bluetooth", Toast.LENGTH_SHORT).show();
                finish();
            }
            mBluetoothLeService.connect(mDeviceAddress);

        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
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
                if (data != null && !data.isEmpty()) {
                    findViewById(R.id.empty_log_view).setVisibility(View.GONE);
                    logTv.setVisibility(View.VISIBLE);
                    logTv.append("Res:" + data + "\n");
                }
            }
        }
    };


    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gatt_services_characteristics);
        logTv = findViewById(R.id.logcat_Tv);
        Clear = (ImageButton) findViewById(R.id.clear_btn);
        Copy = (ImageButton) findViewById(R.id.copy_btn);


        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);
        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
        mConnectionState = (TextView) findViewById(R.id.connection_state);

        getActionBar().setTitle(mDeviceName);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);


        logTv.setMovementMethod(new ScrollingMovementMethod());
        //clear $ copy code in log window
        Clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                logTv.setText("");
                logTv.setVisibility(View.GONE);
                findViewById(R.id.empty_log_view).setVisibility(View.VISIBLE);
                Toast.makeText(DeviceControlActivity.this, "clear", Toast.LENGTH_SHORT).show();
            }
        });

        Copy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("copy", logTv.getText().toString());
                clipboard
                        .setPrimaryClip(clip);
                Toast.makeText(DeviceControlActivity.this, "copied", Toast.LENGTH_SHORT).show();
            }
        });


    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

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
                View dot = findViewById(R.id.connection_dot);
                if (dot != null) {
                    dot.setBackgroundResource(resourceId == R.string.connected ? R.drawable.circle_green : R.drawable.circle_red);
                }
            }
        });
    }


    // Demonstrates how to iterate through the supported GATT Services/Characteristics.
    // In this sample, we populate the data structure that is bound to the ExpandableListView
    // on the UI.
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
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    @Override
    public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {

        compoundButton.getId();
    }


    public void FotaUpdate(View view) {
        if (fotaPasswordAttempts >= MAX_FOTA_ATTEMPTS) {
            showMaxFotaAttemptsDialog();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Password");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("OK", (dialog, which) -> {
            String enteredPassword = input.getText().toString();
            String correctPassword = "1234";

            if (enteredPassword.equals(correctPassword)) {
                fotaPasswordAttempts = 0;
                Intent fotaIntent = new Intent(getApplicationContext(), Fota.class);
                mBluetoothLeService.disconnect();
                fotaIntent.putExtra("device_address", mDeviceAddress);
                fotaIntent.putExtra("device_name", mDeviceName);
                startActivity(fotaIntent);
            } else {
                fotaPasswordAttempts++;

                if (fotaPasswordAttempts < MAX_FOTA_ATTEMPTS) {
                    sendWarningCommandToMeter();
                    findViewById(R.id.empty_log_view).setVisibility(View.GONE);
                    logTv.setVisibility(View.VISIBLE);
                    logTv.append("res: Incorrect Password\n");
                    Toast.makeText(getApplicationContext(), "Incorrect Password. Attempt " + fotaPasswordAttempts + " of 3.", Toast.LENGTH_SHORT).show();

                } else {
                    showMaxFotaAttemptsDialog();
                }
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    private void showMaxFotaAttemptsDialog() {
        // Send the command to the meter before showing the alert
        sendFinalLockCommandToMeter();

        new AlertDialog.Builder(this)
                .setTitle("Maximum Attempts Reached")
                .setMessage("You have entered the wrong password 3 times. Please restart the app to try again.")
                .setCancelable(false)
                .setPositiveButton("Exit", (dialog, which) -> {
                    finishAffinity();
                    System.exit(0);
                })
                .show();
    }
    private void sendWarningCommandToMeter() {
        sendCommandToMeter(new byte[] {
                (byte) 0x55,
                (byte) 0x20,
                (byte) 0x00,
                (byte) 0x01,
                (byte) 0x0B,
                (byte) 0xAA
        });
    }

    private void sendFinalLockCommandToMeter() {
        sendCommandToMeter(new byte[] {
                (byte) 0x55,
                (byte) 0x20,
                (byte) 0x00,
                (byte) 0x01,
                (byte) 0x0C,  
                (byte) 0xAA
        });
    }
    private void sendCommandToMeter(byte[] command) {
        if (mBluetoothLeService != null && mConnected && mwriteCharacteristic != null) {
            mwriteCharacteristic.setValue(command);
            boolean success = mBluetoothLeService.writeCharacteristic(mwriteCharacteristic);
            Log.d("FOTA", "Command sent: " + bytesToHex(command) + ", success=" + success);
        } else {
            Log.e("FOTA", "Cannot send command. Check connection or characteristic.");
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }




}


