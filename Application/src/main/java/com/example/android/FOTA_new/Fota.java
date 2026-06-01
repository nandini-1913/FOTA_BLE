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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Fota extends AppCompatActivity {

    // ─── State Flags ────────────────────────────────────────────────────────────
    private boolean consentReceived        = false;
    private boolean downloadComplete       = false;
    private boolean isIntegrityChecked     = false;
    private boolean isInstallCommandSent   = false;
    private boolean isInstallationComplete = false;
    private int     currentDownloadProgress = 0;
    private android.net.Uri selectedFileUri;

    // ─── File / Slot state ───────────────────────────────────────────────────────
    /**
     * Slot determined by parsing the firmware .txt file's first marker:
     *   {@code @5000}   → slot = 1  →  OTA ACCEPTANCE reply: 55 30 11 00 08 01 01 00 00 AA
     *   {@code @20800}  → slot = 2  →  OTA ACCEPTANCE reply: 55 30 11 00 08 01 00 00 00 AA
     *   unknown → slot = -1 →  error, do not trigger OTA
     */
    private int     detectedSlot   = -1;
    private boolean keyFoundInFile = false;

    // ─── NEW: Firmware data packet buffer (parsed from the .txt file) ───────────
    /**
     *  Each entry is one full "55 ... AA" packet from the firmware file.
     *  These packets are typically several hundred bytes — too large for one
     *  BLE write, so they are sent in BLE_CHUNK_SIZE-byte fragments.
     */
    private final List<byte[]> firmwarePackets = new ArrayList<>();
    private int currentPacketIndex = 0;
    private static final int MAX_RETRY_COUNT = 3;

    private int retryCount = 0;
    private Handler ackTimeoutHandler =
            new Handler(Looper.getMainLooper());

    private Runnable ackTimeoutRunnable;
    private byte[] lastSentPacket = null;
    private int totalPackets       = 0;

    /** Max bytes per BLE write. With requested MTU 255, payload ≈ 244 bytes. */
    private static final int BLE_CHUNK_SIZE      = 240;
    private static final int INTER_FRAGMENT_DELAY_MS = 25;

    // ─── Constants ──────────────────────────────────────────────────────────────
    private static final int    REQUEST_CODE_PERMISSION = 100;
    private static final int    REQUEST_CODE_FILE       = 101;
    private static final String TAG                     = Fota.class.getSimpleName();

    // ─── BLE ────────────────────────────────────────────────────────────────────
    private BluetoothGattCharacteristic mWriteCharacteristic;
    private BluetoothLeService          mBluetoothLeService;
    private String                      mDeviceAddress;
    public  String                      mDeviceName;
    private boolean                     mConnected = false;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<>();

    // ─── UI ─────────────────────────────────────────────────────────────────────
    private TextView    mConnectionState, debug_logtv, progressTextView, mEtDownloadedFile;
    private ScrollView  mLogScrollView;
    private View        mCircleState, mintegrityState, minstallationState;
    private ProgressBar mProgressBar;
    private EditText    mfilePath;
    private Button      mDownloadButton, mIntegrityButton, mInstallButton, mInstallCheck;
    private ImageButton Clear, Copy;
    private Spinner     versionSpinner;
    private String      selectedVersion = "RIO_DA";

    // ─── Firmware key map (4-byte key embedded in every firmware file) ───────────
    private final Map<String, byte[]> firmwareKeyMap = new HashMap<String, byte[]>() {{
        put("RIO_DA",    new byte[]{(byte)0xA9,(byte)0xF3,(byte)0xC1,(byte)0x20});
        put("RIO_G4",    new byte[]{(byte)0x3B,(byte)0x2E,(byte)0x47,(byte)0xD9});
        put("RIO_G6",    new byte[]{(byte)0x7C,(byte)0x1D,(byte)0x80,(byte)0xFA});
        put("RIO_G10",   new byte[]{(byte)0xF2,(byte)0xBE,(byte)0x34,(byte)0x26});
        put("RIO_G16",   new byte[]{(byte)0x11,(byte)0xDC,(byte)0x5A,(byte)0x03});
        put("RIO_G25",   new byte[]{(byte)0x4F,(byte)0xA9,(byte)0xC6,(byte)0x7B});
        put("VOLTA_DA",  new byte[]{(byte)0xD5,(byte)0x83,(byte)0x41,(byte)0xEC});
        put("VOLTA_G4",  new byte[]{(byte)0x0C,(byte)0x2B,(byte)0x7E,(byte)0x98});
        put("VOLTA_G6",  new byte[]{(byte)0x9E,(byte)0xD0,(byte)0x1F,(byte)0x6A});
        put("VOLTA_G10", new byte[]{(byte)0x6A,(byte)0xF4,(byte)0xBB,(byte)0x35});
        put("VOLTA_G16", new byte[]{(byte)0x2F,(byte)0x6C,(byte)0x8D,(byte)0xE7});
        put("VOLTA_G25", new byte[]{(byte)0x83,(byte)0x32,(byte)0xD9,(byte)0x10});
    }};

    // ════════════════════════════════════════════════════════════════════════════
    //  SERVICE CONNECTION
    // ════════════════════════════════════════════════════════════════════════════
    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                Toast.makeText(getApplicationContext(),
                        "Unable to initialize Bluetooth", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            mBluetoothLeService.disconnect();
            mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "BLE service connected, device=" + mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    // ════════════════════════════════════════════════════════════════════════════
    //  GATT BROADCAST RECEIVER
    // ════════════════════════════════════════════════════════════════════════════
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

            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                log("GATT Services discovered. Setting up characteristics...");
                displayGattServices(mBluetoothLeService.getSupportedGattServices());
                setupNotifyCharacteristic();

                // Request larger MTU for faster FOTA
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (mBluetoothLeService != null) {
                        boolean ok = mBluetoothLeService.requestMtu(255);
                        log("Requesting MTU 255... " + (ok ? "sent" : "failed"));
                    }
                }, 500);

            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                // ── IMPORTANT: get raw hex bytes, NOT .getBytes() on string ──
                byte[] rawData = intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA_BYTES);
                if (rawData != null) {
                    debug_logtv.append("OTA RES: " + bytesToHex(rawData) + "\n");
                    onMeterResponseReceived(rawData);
                }
            }
        }
    };

    // ════════════════════════════════════════════════════════════════════════════
    //  onCreate
    // ════════════════════════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fota_layout);

        Intent intent = getIntent();
        if (intent != null) {
            mDeviceAddress = intent.getStringExtra("device_address");
            mDeviceName    = intent.getStringExtra("device_name");
        }

        if (getSupportActionBar() != null)
            getSupportActionBar().hide();

        bindService(new Intent(this, BluetoothLeService.class),
                mServiceConnection, BIND_AUTO_CREATE);

        // ── View references ──────────────────────────────────────────────────
        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
        mCircleState      = findViewById(R.id.cons_circle_state);
        mintegrityState   = findViewById(R.id.integrity_circle);
        minstallationState = findViewById(R.id.installation_circle);
        mProgressBar      = findViewById(R.id.progress_bar);
        progressTextView  = findViewById(R.id.progressTextView);
        mDownloadButton   = findViewById(R.id.download_btn);
        mIntegrityButton  = findViewById(R.id.btn_integrity);
        mInstallButton    = findViewById(R.id.btn_install);
        mInstallCheck     = findViewById(R.id.btn_installcheck);
        mfilePath         = findViewById(R.id.ET_filePath);
        mEtDownloadedFile = findViewById(R.id.progress_status_label);
        mConnectionState  = findViewById(R.id.connection_state);
        debug_logtv       = findViewById(R.id.debug_logcat);
        mLogScrollView    = findViewById(R.id.log_scroll_view);
        Clear             = findViewById(R.id.clear_btn);
        Copy              = findViewById(R.id.copy_btn);
        versionSpinner    = findViewById(R.id.spinner_version);

        // ── Spinner ──────────────────────────────────────────────────────────
        List<String> versions = new ArrayList<>();
        versions.add("RIO_DA");   versions.add("RIO_G4");    versions.add("RIO_G6");
        versions.add("RIO_G10");  versions.add("RIO_G16");   versions.add("RIO_G25");
        versions.add("VOLTA_DA"); versions.add("VOLTA_G4");  versions.add("VOLTA_G6");
        versions.add("VOLTA_G10");versions.add("VOLTA_G16"); versions.add("VOLTA_G25");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, versions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        versionSpinner.setAdapter(adapter);
        versionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                selectedVersion = p.getItemAtPosition(pos).toString();
                debug_logtv.append("Selected version: " + selectedVersion + "\n");
                if (selectedFileUri != null) {
                    byte[] key = firmwareKeyMap.get(selectedVersion);
                    if (key != null) parseFirmwareFile(selectedFileUri, key);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        // ── Storage permission ───────────────────────────────────────────────
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_CODE_PERMISSION);
        }

        // ── Button listeners ─────────────────────────────────────────────────
        Button consentButton = findViewById(R.id.btn_consent);
        consentButton.setOnClickListener(v -> onOtaTriggerButtonClick());

        mDownloadButton .setOnClickListener(v ->
                log("Download starts automatically after OTA acceptance."));
        mIntegrityButton.setOnClickListener(v -> onIntegrityButtonClick());
        mInstallButton  .setOnClickListener(v -> onInstallButtonClick());
        mInstallCheck   .setOnClickListener(v -> onInstallCheckButtonClick());

        Clear.setOnClickListener(v -> {
            debug_logtv.setText("");
            Toast.makeText(Fota.this, "Cleared", Toast.LENGTH_SHORT).show();
        });
        Copy.setOnClickListener(v -> {
            ClipboardManager cb =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cb.setPrimaryClip(
                    ClipData.newPlainText("copy", debug_logtv.getText().toString()));
            Toast.makeText(Fota.this, "Copied", Toast.LENGTH_SHORT).show();
        });

        debug_logtv.setMovementMethod(new ScrollingMovementMethod());
        debug_logtv.append("LOG WINDOW — FOTA UPGRADE\n");
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  STEP 1 — OTA TRIGGER (APP → DEVICE)
    //
    //  Protocol: SOF=0x55, CMD_H=0x30, CMD_L=0x10, LEN_H=0x00, LEN_L=0x00,
    //            DATA= FIRMWARE_KEY (4 bytes), EOF=0xAA
    //  Frame:    55 30 10 00 00 [K0 K1 K2 K3] AA   (10 bytes total)
    // ════════════════════════════════════════════════════════════════════════════
    private void onOtaTriggerButtonClick() {
        if (!mConnected) { log("Error: Device not connected"); return; }
        if (selectedFileUri == null) { log("Error: Select firmware file first"); return; }

        byte[] key = firmwareKeyMap.get(selectedVersion);
        if (key == null) { log("Error: Invalid version"); return; }

        if (!keyFoundInFile || detectedSlot == -1) {
            parseFirmwareFile(selectedFileUri, key);
        }

        if (!keyFoundInFile) {
            log("❌ Key for [" + selectedVersion + "] NOT found.");
            return;
        }
        if (detectedSlot == -1) {
            log("❌ No valid slot marker found.");
            return;
        }

        // ── Check BLE ready BEFORE building frame ────────────────────────────
        if (mWriteCharacteristic == null) {
            // Try one last attempt if services were discovered but characteristics not set
            if (mBluetoothLeService != null && mGattCharacteristics != null && !mGattCharacteristics.isEmpty()) {
                log("Attempting to recover BLE setup...");
                setupNotifyCharacteristic();
            }

            if (mWriteCharacteristic == null) {
                log("❌ Cannot send OTA Trigger — BLE not ready (characteristic null).");
                log("   Wait for BLE connection to complete, then try again.");
                return;
            }
        }
        if (mBluetoothLeService == null) {
            log("❌ Cannot send OTA Trigger — BLE service not ready.");
            return;
        }

        log("✅ File validated → Key found, " + slotLabel());

        byte[] frame = new byte[10];
        frame[0] = (byte) 0x55;
        frame[1] = (byte) 0x30;
        frame[2] = (byte) 0x10;
        frame[3] = (byte) 0x00;
        frame[4] = (byte) 0x05;
        System.arraycopy(key, 0, frame, 5, 4);
        frame[9] = (byte) 0xAA;

        boolean sent = sendRawCommand(frame);

        if (sent) {
            log("▶ OTA Trigger sent (" + selectedVersion + " / " + slotLabel() + "): "
                    + bytesToHex(frame));
            log("Waiting for meter key acceptance...");
        } else {
            log("❌ OTA Trigger FAILED to send — check BLE connection.");
        }
    }

    public void OtaStart(View view)    { onOtaTriggerButtonClick(); }
    public void StartUpdate(View view) { onInstallButtonClick(); }

    private void onIntegrityButtonClick() {
        if (!mConnected)       { log("Error: Not connected"); return; }
        if (!downloadComplete) { log("Error: Download not complete"); return; }

        byte[] frame = new byte[] {
                (byte)0x55, (byte)0x30, (byte)0x00, (byte)0x00, (byte)0x01, (byte)0x01, (byte)0xAA
        };

        lastSentPacket = frame;
        log("STEP3 ▶ Sending Integrity Check command: " + bytesToHex(frame));
        boolean ok = sendRawCommand(frame);
        if (ok) {
            startAckTimeout();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  METER RESPONSE HANDLER — dispatches all incoming frames
    // ════════════════════════════════════════════════════════════════════════════
    private void onMeterResponseReceived(byte[] raw) {
        if (raw == null || raw.length == 0)
        { return; }

        if (raw.length < 2)
        { return; }
        if ((raw[0] & 0xFF) != 0x55 || (raw[1] & 0xFF) != 0x30)
        { log("DEBUG: " + new String(raw));
            return; }

        if (raw.length < 5) {
            log("Warning: Frame too short");
            return;
        }
        int cmdH = raw[1] & 0xFF;
        int cmdL = raw[2] & 0xFF;
        int lenH = raw[3] & 0xFF;
        int lenL = raw[4] & 0xFF;

        if (cmdH != 0x30) {
            log("Warning: Unexpected CMD_H 0x" + String.format("%02X", cmdH));
            return;
        }

        switch (cmdL) {
            case 0x01:
                handleCmd01(lenH, lenL, raw);
                break;
            case 0x11:
                handleCmd11(lenH, lenL, raw);
                break;
            case 0x21:
                handleCmd21(lenH, lenL, raw);
                break;
            case 0x31:
                handleCmd31(lenH, lenL, raw);
                break;
            default:
                log("Warning: Unhandled CMD_L 0x" + String.format("%02X", cmdL));
                break;
        }
    }

    private void handleCmd01(int lenH, int lenL, byte[] raw)
    {
        if (raw == null || raw.length < 6)
        {
            log("STEP3 ❌ Invalid CRC response");
            return;
        }


        log("CRC CHECK LOOP ENTERED");
        int crc_check = raw[5] & 0xFF;

        stopAckTimeout();

        retryCount = 0;

        if (crc_check == 0x01)
        {
            isIntegrityChecked = true;
            downloadComplete = true;
            log("STEP3 ✅ Integrity check PASSED!");



            runOnUiThread(() -> mintegrityState.setBackgroundResource(R.drawable.circle_green));
            Toast.makeText(
                    Fota.this,
                    "Integrity check passed ✅",
                    Toast.LENGTH_SHORT).show();

            return;
        }
        else if (crc_check == 0x00)
        {
            isIntegrityChecked = false;
            downloadComplete = false;
            log("STEP3 ❌ Integrity check failed!");

            runOnUiThread(() -> mintegrityState.setBackgroundResource(R.drawable.circle_red));

            Toast.makeText(
                    Fota.this,
                    "Integrity check failed ❌",
                    Toast.LENGTH_SHORT).show();

            return;
        }
    }


    // ════════════════════════════════════════════════════════════════════════════
    //  CMD 0x11 HANDLER — OTA ACCEPTANCE / SLOT HANDSHAKE
    // ════════════════════════════════════════════════════════════════════════════
    private void handleCmd11(int lenH, int lenL, byte[] raw) {

        if (lenH == 0x00 && lenL == 0x00) {
            log("STEP2 ▶ Key accepted by device. Sending OTA Acceptance (slot reply)...");
            sendSlotAcceptanceFrame();

        } else if (lenH == 0x00 && lenL == 0x05) {
            if (raw.length >= 6) {
                int d0 = raw[5] & 0xFF;
                if (d0 == 0x01) {
                    consentReceived = true;
                    log("STEP2 ✅ OTA ACCEPTED by device — starting download...");
                    runOnUiThread(() -> mCircleState.setBackgroundResource(R.drawable.circle_green));
                    new Handler(Looper.getMainLooper()).postDelayed(this::sendDownloadRequest, 300);
                } else if (d0 == 0x00) {
                    log("STEP2 ❌ OTA REJECTED by device — wrong key or slot.");
                    runOnUiThread(() -> Toast.makeText(Fota.this,
                            "OTA Rejected by device ❌", Toast.LENGTH_LONG).show());
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  SEND SLOT ACCEPTANCE FRAME (APP → DEVICE)
    // ════════════════════════════════════════════════════════════════════════════
    private void sendSlotAcceptanceFrame() {
        if (!keyFoundInFile) {
            log("Error: Key not found in file — cannot send slot acceptance.");
            return;
        }
        if (detectedSlot == -1) {
            log("Error: Unknown slot — cannot send slot acceptance.");
            return;
        }

        byte slotIdByte = (detectedSlot == 1) ? (byte) 0x01 : (byte) 0x00;

        byte[] frame = new byte[]{
                (byte) 0x55, (byte) 0x30, (byte) 0x11, (byte) 0x00, (byte) 0x08,
                (byte) 0x01, slotIdByte, (byte) 0x00, (byte) 0x00, (byte) 0xAA
        };

        log("STEP2 ▶ Slot acceptance sent (" + slotLabel() + "): " + bytesToHex(frame));
        sendRawCommand(frame);
    }
    private void retryLastPacket()
    {
        if (retryCount >= MAX_RETRY_COUNT)
        {
            log("❌ Packet "
                    + (currentPacketIndex + 1)
                    + " failed after "
                    + MAX_RETRY_COUNT
                    + " retries.");

            runOnUiThread(() ->
                    Toast.makeText(
                            Fota.this,
                            "OTA Failed ❌",
                            Toast.LENGTH_LONG).show());

            return;
        }

        retryCount++;

        log("🔄 Retrying packet "
                + (currentPacketIndex + 1)
                + " (Attempt "
                + retryCount
                + "/"
                + MAX_RETRY_COUNT
                + ")");

        if (lastSentPacket != null)
        {
            new Handler(Looper.getMainLooper())
                    .postDelayed(() -> {

                        boolean ok =
                                sendRawCommand(lastSentPacket);

                        if (ok)
                        {
                            log("Retry packet sent");

                            // IMPORTANT
                            startAckTimeout();
                        }
                        else
                        {
                            log("Retry send failed");

                            retryLastPacket();
                        }

                    }, 300);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  CMD 0x21 HANDLER — OTA DATA ACCEPTANCE / FAIL
    //
    //  CHANGED: on each ACK, advance to the NEXT firmware packet (sent in BLE
    //           fragments). When all packets are sent, fire the integrity check.
    // ════════════════════════════════════════════════════════════════════════════
    private void handleCmd21(int lenH, int lenL, byte[] raw) {
        if (lenH != 0x00 || lenL != 0x05) {
            log("⚠ Corrupted ACK detected: "
                    + String.format("%02X %02X", lenH, lenL));

            retryLastPacket();

            return;
        }

        if (raw.length < 6) {
            log("Error: CMD 0x21 frame too short");
            return;
        }

        int d0 = raw[5] & 0xFF;

        if (d0 == 0x01) {
            // ── Data chunk ACKed ──────────────────────────────────────────────


            // ── Firmware packet ACKed → send next packet, or finish ───────────
            if (totalPackets <= 0) {
                log("Warning: ACK received but no packets queued.");
                return;
            }

            log("Packet " + (currentPacketIndex + 1) + "/" + totalPackets + " ACKed.");
            stopAckTimeout();
            retryCount = 0;
            final int progress = (int) (((currentPacketIndex + 1L) * 100L) / totalPackets);
            currentDownloadProgress = progress;
            runOnUiThread(() -> {
                mProgressBar.setProgress(progress, true);
                progressTextView.setText(progress + "%");
                scrollLog();
            });

            currentPacketIndex++;

            if (currentPacketIndex < totalPackets) {
                // Send next firmware packet (fragmented)
                new Handler(Looper.getMainLooper())
                        .postDelayed(this::sendCurrentFirmwarePacket, 500);
            } else {
                // All packets sent — set downloadComplete and wait for device CRC result
                downloadComplete = true;
                runOnUiThread(() -> {
                    mProgressBar.setProgress(100, true);
                    progressTextView.setText("100%");
                });
                log("STEP3 ✅ All " + totalPackets + " firmware packets sent (100%)!");
                log("Waiting for device CRC result...");

            }

        } else if (d0 == 0x00) {
            int reason = (raw.length >= 7) ? (raw[6] & 0xFF) : -1;
            if (!downloadComplete) {
                log("STEP3 ❌ OTA DATA FAIL on packet " + (currentPacketIndex + 1) +
                        ". Rejection reason: 0x" + String.format("%02X", reason));
            } else {
                log("❌ Integrity check FAILED. Reason: 0x"
                        + String.format("%02X", reason));
            }
            runOnUiThread(() -> Toast.makeText(Fota.this,
                    "OTA Data Fail ❌", Toast.LENGTH_LONG).show());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  CMD 0x31 HANDLER — OTA END RESPONSE (Install result)
    // ════════════════════════════════════════════════════════════════════════════
    private void handleCmd31(int lenH, int lenL, byte[] raw) {
        if (lenH != 0x00 || lenL != 0x07) {
            log("Warning: CMD 0x31 unexpected length: "
                    + String.format("%02X %02X", lenH, lenL));
            return;
        }

        if (raw.length < 6) {
            log("Error: CMD 0x31 frame too short");
            return;
        }

        int d0 = raw[5] & 0xFF;
        int d1 = raw[6] & 0xFF;
        if (d0 == 0x01) {
            isInstallationComplete = true;
            log("STEP4 ✅ FW installed successfully!");

            runOnUiThread(() -> minstallationState.setBackgroundResource(R.drawable.circle_green));
            Toast.makeText(Fota.this, "FW installed successfully ✅",
                    Toast.LENGTH_LONG).show();

        } else {
            if(d1==0xE2)
            {   Toast.makeText(Fota.this, "No BSL in MCU ❌",
                    Toast.LENGTH_LONG).show();}
            else if(d1==0xE3)
            {   Toast.makeText(Fota.this, "NO ACTIVE OTA SESSION❌",
                    Toast.LENGTH_LONG).show();
            }

            log("STEP4 ❌ OTA END RESPONSE REJECTED by device.");
            runOnUiThread(() -> minstallationState.setBackgroundResource(R.drawable.circle_red));
            Toast.makeText(Fota.this, "FW installation rejected ❌",
                    Toast.LENGTH_LONG).show();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  STEP 3 — OTA DATA REQUEST (APP → DEVICE)        ← CHANGED
    //
    //  NEW BEHAVIOUR:
    //    1. Parse all "55 ... AA" packets out of the firmware .txt file.
    //    2. Send the FIRST packet — fragmented into BLE_CHUNK_SIZE-byte writes.
    //    3. Each subsequent packet is sent on receipt of CMD 0x21 ACK
    //       (see handleCmd21).
    // ════════════════════════════════════════════════════════════════════════════
    private void sendDownloadRequest() {
        if (!mConnected || !consentReceived) return;

        // Parse firmware data packets (skip @marker line)
        firmwarePackets.clear();
        if (selectedFileUri != null) {
            parseFirmwarePackets(selectedFileUri);
        }

        totalPackets        = firmwarePackets.size();
        currentPacketIndex  = 0;
        currentDownloadProgress = 0;
        downloadComplete    = false;
        isIntegrityChecked  = false;

        if (totalPackets == 0) {
            log("❌ No firmware data packets found in file — aborting download.");
            return;
        }

        log("STEP3 ▶ Download started. " + totalPackets + " packets to send.");

        runOnUiThread(() -> {
            mProgressBar.setVisibility(View.VISIBLE);
            progressTextView.setVisibility(View.VISIBLE);
            mProgressBar.setProgress(0, true);
            progressTextView.setText("0%");
        });

        sendCurrentFirmwarePacket();
    }

    /**
     * Sends the firmware packet at {@code currentPacketIndex}, splitting it
     * into BLE_CHUNK_SIZE-byte fragments. The device should respond with one
     * CMD 0x21 ACK after the full packet is received — that ACK triggers the
     * next packet via {@link #handleCmd21}.
     */
    private void sendCurrentFirmwarePacket() {
        if (currentPacketIndex >= totalPackets) return;

        byte[] packet = firmwarePackets.get(currentPacketIndex);
        lastSentPacket = packet;
        if (packet == null || packet.length == 0) {
            log("Skipping empty packet at index " + currentPacketIndex);
            currentPacketIndex++;
            sendCurrentFirmwarePacket();
            return;
        }
        int progress = 0;

// Progress embedded inside firmware packet
        if (packet.length > 5 &&
                packet[0] == (byte)0x55 &&
                packet[1] == (byte)0x30 &&
                packet[2] == (byte)0x20)
        {
            progress = packet[5] & 0xFF;
            if (progress > 100)
                progress = 100;
        }

        final int finalProgress = progress;

        runOnUiThread(() -> {

            mEtDownloadedFile.setText(
                    "Downloading : " + finalProgress + "%");
        });

        log("▶ Packet " + (currentPacketIndex + 1) + "/" + totalPackets +
                " — Sending...");
        boolean ok = sendRawCommand(packet);
        if (ok)
        {
            startAckTimeout();
        }
    }
    private void stopAckTimeout()
    {
        if (ackTimeoutRunnable != null)
        {
            ackTimeoutHandler.removeCallbacks(
                    ackTimeoutRunnable);
        }
    }
    private void startAckTimeout()
    {
        stopAckTimeout();

        ackTimeoutRunnable = () -> {

            log("⏰ ACK timeout for packet "
                    + (currentPacketIndex + 1));

            retryLastPacket();
        };

        ackTimeoutHandler.postDelayed(
                ackTimeoutRunnable,
                3000);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  STEP 4 — OTA END REQUEST / INSTALL
    // ════════════════════════════════════════════════════════════════════════════
    private void onInstallButtonClick() {
        if (!mConnected)         { log("Error: Not connected"); return; }
        if (!consentReceived)    { log("Error: Consent not received"); return; }
        if (!downloadComplete)   { log("Error: Download not complete"); return; }
        if (!isIntegrityChecked) { log("Error: Integrity check not passed"); return; }
        if (isInstallCommandSent){ log("Warning: Install already triggered"); return; }

        byte[] frame = new byte[]{
                (byte) 0x55, (byte) 0x30, (byte) 0x30, (byte) 0x00, (byte) 0x07,
                (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0xAA
        };

        sendRawCommand(frame);
        isInstallCommandSent = true;
        log("STEP4 ▶ OTA End (Install) command sent: " + bytesToHex(frame));

        new Handler(Looper.getMainLooper()).postDelayed(this::sendInstallCheckCommand, 3000);
    }

    private void onInstallCheckButtonClick() {
        if (!mConnected)          { log("Error: Not connected"); return; }
        if (!consentReceived)     { log("Error: Consent not received"); return; }
        if (!downloadComplete)    { log("Error: Download not complete"); return; }
        if (!isIntegrityChecked)  { log("Error: Integrity check not passed"); return; }
        if (!isInstallCommandSent){ log("Error: Install not started"); return; }
        sendInstallCheckCommand();
    }

    private void sendInstallCheckCommand() {
        byte[] frame = new byte[]{
                (byte) 0x55, (byte) 0x30, (byte) 0x31, (byte) 0x00, (byte) 0x07,
                (byte) 0x01, (byte) 0xAA
        };
        sendRawCommand(frame);
        log("STEP4 ▶ Install check sent: " + bytesToHex(frame));
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  FIRMWARE FILE PARSER — slot/key detector (unchanged)
    // ════════════════════════════════════════════════════════════════════════════
    private void parseFirmwareFile(android.net.Uri fileUri, byte[] keyToFind) {
        detectedSlot   = -1;
        keyFoundInFile = false;

        StringBuilder keyHex = new StringBuilder();
        for (int i = 0; i < keyToFind.length; i++) {
            if (i > 0) keyHex.append(" ");
            keyHex.append(String.format("%02X", keyToFind[i] & 0xFF));
        }
        String keyPattern = keyHex.toString().toUpperCase();
        log("Scanning file for key [" + keyPattern + "] and slot...");

        try (InputStream is = getContentResolver().openInputStream(fileUri);
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            boolean slotMarkerFound = false;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (!slotMarkerFound && line.startsWith("@")) {
                    slotMarkerFound = true;
                    String marker = line.substring(1).trim();
                    switch (marker.toUpperCase()) {
                        case "5000":
                            detectedSlot = 1;
                            log("  @5000 found → Slot 1");
                            break;
                        case "20800":
                            detectedSlot = 2;
                            log("  @20800 found → Slot 2");
                            break;
                        default:
                            detectedSlot = -1;
                            log("  Unknown marker @" + marker);
                            break;
                    }
                    continue;
                }

                if (!keyFoundInFile && line.toUpperCase().contains(keyPattern)) {
                    keyFoundInFile = true;
                    log("  Key [" + keyPattern + "] found in file ✅");
                }

                if (slotMarkerFound && keyFoundInFile) break;
            }

        } catch (IOException e) {
            log("Error reading firmware file: " + e.getMessage());
        }

        if (!keyFoundInFile) log("  Key [" + keyPattern + "] NOT found in file ❌");
        if (detectedSlot == -1) log("  No valid slot marker found in file ❌");
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  NEW: FIRMWARE PACKET PARSER
    //
    //  Walks the .txt file line by line. Skips @marker lines. Each remaining
    //  non-empty line is parsed as a hex stream — must start with 0x55 and end
    //  with 0xAA to be considered a valid firmware packet.
    // ════════════════════════════════════════════════════════════════════════════
    private void parseFirmwarePackets(android.net.Uri fileUri) {
        firmwarePackets.clear();

        // The target marker we want to start reading from
        String targetMarker = (detectedSlot == 1) ? "@5000" : (detectedSlot == 2) ? "@20800" : null;
        if (targetMarker == null) {
            log("❌ Error: Cannot parse packets, unknown slot.");
            return;
        }

        try (InputStream is = getContentResolver().openInputStream(fileUri);
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {

            String line;
            boolean foundStart = false;
            int valid = 0, skipped = 0;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Stop if we encounter ANY other @ marker after we already started
                if (foundStart && line.startsWith("@")) {
                    log("  Reached next slot marker [" + line + "] — stopping parser.");
                    break;
                }

                // Check if this is our starting marker
                if (line.equalsIgnoreCase(targetMarker)) {
                    foundStart = true;
                    log("  Starting packet collection after [" + targetMarker + "]");
                    continue;
                }

                if (!foundStart) continue;

                // Collect packets (hex lines)
                byte[] bytes = hexLineToBytes(line);
                if (bytes == null || bytes.length < 2) {
                    skipped++;
                    continue;
                }
                // Verify packet SOF/EOF
                if ((bytes[0] & 0xFF) != 0x55 ||
                        (bytes[bytes.length - 1] & 0xFF) != 0xAA) {
                    skipped++;
                    continue;
                }
                firmwarePackets.add(bytes);
                valid++;
            }
            log("Firmware parsed: " + valid + " packets for " + slotLabel() + " ✅  (" + skipped + " skipped)");
        } catch (IOException e) {
            log("Error reading firmware packets: " + e.getMessage());
        }
    }

    /** Converts a space-separated hex string ("55 20 04 ... AA") to bytes. */
    private static byte[] hexLineToBytes(String line) {
        // Tolerate any whitespace separator
        String[] tokens = line.split("\\s+");
        if (tokens.length == 0) return null;

        try {
            byte[] out = new byte[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                String t = tokens[i];
                if (t.length() != 2) return null;          // must be 2 hex chars
                out[i] = (byte) Integer.parseInt(t, 16);
            }
            return out;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String slotLabel() {
        if (detectedSlot == 1) return "Slot1 (@5000)";
        if (detectedSlot == 2) return "Slot2 (@20800)";
        return "Unknown Slot";
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  BLE NOTIFY SETUP (unchanged)
    // ════════════════════════════════════════════════════════════════════════════
    private void setupNotifyCharacteristic() {

        if (mGattCharacteristics == null || mGattCharacteristics.isEmpty()) {
            log("❌ No GATT services found. BLE discovery may not have completed.");
            return;
        }

        if (mGattCharacteristics.size() < 4) {
            setupNotifyCharacteristicFallback();
            return;
        }

        if (mGattCharacteristics.get(3).isEmpty()) {
            setupNotifyCharacteristicFallback();
            return;
        }

        final BluetoothGattCharacteristic characteristic = mGattCharacteristics.get(3).get(0);
        final int charaProp = characteristic.getProperties();

        if ((charaProp & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            mBluetoothLeService.setCharacteristicNotification(characteristic, true);
            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                mBluetoothLeService.writeDescriptor(descriptor);
            } else {
                log("⚠ CCCD descriptor not found on: " + characteristic.getUuid());
            }
        } else {
            setupNotifyCharacteristicFallback();
            return;
        }

        if ((charaProp & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                (charaProp & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            mWriteCharacteristic = characteristic;
        } else {
            setupNotifyCharacteristicFallback();
        }
    }

    private void setupNotifyCharacteristicFallback() {
        BluetoothGattCharacteristic notifyChar = null;
        BluetoothGattCharacteristic writeChar  = null;

        for (int i = 0; i < mGattCharacteristics.size(); i++) {
            for (int j = 0; j < mGattCharacteristics.get(i).size(); j++) {
                BluetoothGattCharacteristic c = mGattCharacteristics.get(i).get(j);
                int props = c.getProperties();

                if (notifyChar == null &&
                        (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    notifyChar = c;
                }

                if (writeChar == null &&
                        ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                                (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)) {
                    writeChar = c;
                }

                if (notifyChar != null && writeChar != null) break;
            }
            if (notifyChar != null && writeChar != null) break;
        }

        if (notifyChar != null) {
            mBluetoothLeService.setCharacteristicNotification(notifyChar, true);
            BluetoothGattDescriptor descriptor = notifyChar.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                mBluetoothLeService.writeDescriptor(descriptor);
            } else {
                log("⚠ Fallback: CCCD descriptor not found on: " + notifyChar.getUuid());
            }
        } else {
            log("❌ Fallback: No NOTIFY characteristic found in any service!");
        }

        if (writeChar != null) {
            mWriteCharacteristic = writeChar;
        } else {
            log("❌ Fallback: No WRITE characteristic found — BLE write will fail!");
            mWriteCharacteristic = null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  HELPERS
    // ════════════════════════════════════════════════════════════════════════════
    private boolean sendRawCommand(byte[] command) {
        if (!mConnected) {
            log("Error: Not connected");
            return false;
        }
        if (mWriteCharacteristic == null) {
            log("Error: Write characteristic null");
            return false;
        }
        if (mBluetoothLeService == null) {
            log("Error: BLE service null");
            return false;
        }
        mWriteCharacteristic.setValue(command);
        boolean success = mBluetoothLeService.writeCharacteristic(mWriteCharacteristic);
        if (!success) log("Error: BLE write failed: " + bytesToHex(command));
        return success;
    }

    private void log(String msg) {
        runOnUiThread(() -> { debug_logtv.append(msg + "\n"); scrollLog(); });
    }

    private void scrollLog() {
        if (mLogScrollView != null) {
            mLogScrollView.post(() -> mLogScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(() -> {
            mConnectionState.setText(resourceId);
            int colorRes = (resourceId == R.string.connected) ? R.color.green : R.color.red;
            int color = ContextCompat.getColor(Fota.this, colorRes);
            TextViewCompat.setCompoundDrawableTintList(mConnectionState, android.content.res.ColorStateList.valueOf(color));
        });
    }

    private void displayGattServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null) return;
        mGattCharacteristics = new ArrayList<>();
        for (BluetoothGattService s : gattServices)
            mGattCharacteristics.add(new ArrayList<>(s.getCharacteristics()));
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  FILE BROWSER
    // ════════════════════════════════════════════════════════════════════════════
    public void BrowesFile(View view) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(
                Intent.createChooser(intent, "Select Firmware File (.txt)"),
                REQUEST_CODE_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_FILE && resultCode == RESULT_OK && data != null) {
            selectedFileUri = data.getData();
            String displayName = getFileName(selectedFileUri);
            mfilePath.setText(displayName);
            log("File selected: " + displayName);

            byte[] key = firmwareKeyMap.get(selectedVersion);
            if (key != null && selectedFileUri != null) {
                parseFirmwareFile(selectedFileUri, key);
                if (!keyFoundInFile) {
                    log("⚠ Key for [" + selectedVersion + "] not found — check version.");
                } else {
                    log("File OK: key present, " + slotLabel());
                }
            }
        }
    }

    private String getFileName(android.net.Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            android.database.Cursor cursor =
                    getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx != -1) result = cursor.getString(idx);
                }
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) result = result.substring(cut + 1);
        }
        return result;
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ════════════════════════════════════════════════════════════════════════════
    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter(),
                    Context.RECEIVER_EXPORTED);
        else
            registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());

        boolean fotaInProgress = consentReceived && !isIntegrityChecked;
        if (fotaInProgress) return;

        if (mBluetoothLeService != null) {
            mBluetoothLeService.connect(mDeviceAddress);

            if (mBluetoothLeService.getSupportedGattServices() != null) {
                log("Already connected, initializing services...");
                displayGattServices(mBluetoothLeService.getSupportedGattServices());
                setupNotifyCharacteristic();
            }
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

    // ════════════════════════════════════════════════════════════════════════════
    //  OPTIONS MENU
    // ════════════════════════════════════════════════════════════════════════════
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        menu.findItem(R.id.menu_connect).setVisible(!mConnected);
        menu.findItem(R.id.menu_disconnect).setVisible(mConnected);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_connect:    mBluetoothLeService.connect(mDeviceAddress); return true;
            case R.id.menu_disconnect: mBluetoothLeService.disconnect();            return true;
            case android.R.id.home:    onBackPressed();                             return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  PERMISSIONS
    // ════════════════════════════════════════════════════════════════════════════
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSION &&
                (grantResults.length == 0 ||
                        grantResults[0] != PackageManager.PERMISSION_GRANTED))
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show();
    }

    static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter f = new IntentFilter();
        f.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        f.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        f.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        f.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return f;
    }
}