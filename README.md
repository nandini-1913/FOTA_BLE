# 📡 FOTA_BLE — Firmware Over The Air via Bluetooth LE (Android)

An Android application that enables **wireless firmware upgrades** for BLE-enabled embedded devices using the OTA (Over-The-Air) protocol over Bluetooth Low Energy GATT.

---

## 🚀 Features

- 🔵 **BLE Device Discovery & Connection** — Scan, connect, and manage BLE devices seamlessly
- 📦 **Firmware File Selection** — Browse and load firmware `.txt` (converted binary) files from device storage
- 📡 **OTA Channel Support** — Supports multiple OTA channels (`VOLTA_DA`, `RIO_DA`, etc.)
- 🔄 **OTA Trigger** — Initiates the firmware upgrade handshake with the target device
- 📥 **Chunked Firmware Download** — Sends firmware in 258 packets with ACK verification per packet
- ✅ **3-Step Status Tracking** — Visual progress through CONSENT → INTEGRITY → INSTALLED stages
- 📊 **Real-time Progress Bar** — Live download percentage display (0–100%)
- 🖥️ **Console Log Window** — Live hex dump and status messages for debugging
- 🔒 **CRC Verification** — Device-side CRC check after all packets are sent
- 💡 **FOTA Upgradation Screen** — Simple one-tap upgrade flow for end-users

---

## 📱 Screenshots

| FOTA Home | FOTA Manager | Upgrading | Complete |
| APP IMAGES.png)  |

---

## 🛠️ Tech Stack

- **Language:** Java (Android)
- **BLE API:** Android BluetoothGatt / BluetoothLeScanner
- **Protocol:** Custom OTA over BLE GATT characteristics
- **Build System:** Gradle
- **Min SDK:** Android SDK 28+

---

## 📋 How It Works

1. App scans and connects to target BLE device via MAC address
2. GATT services are discovered; MTU is negotiated (255 bytes)
3. User selects the firmware file and OTA channel
4. **OTA Trigger** initiates the upgrade handshake
5. Firmware is split into packets and sent with ACK per packet
6. Device performs CRC validation after all packets are received
7. Install command is sent; device reboots with new firmware
8. All 3 steps (CONSENT, INTEGRITY, INSTALLED) turn green ✅

---

## ⚙️ Getting Started

```bash
git clone https://github.com/nandini-1913/FOTA_BLE.git
```

Open in **Android Studio**, let Gradle sync, then build and run on a physical Android device (BLE requires real hardware).

> ⚠️ Bluetooth permissions and Location permission required on Android 10+.

---

## 📁 Project Structure
FOTA_BLE/
├── Application/       # Main Android app source
├── android/           # Android-specific configs
├── daemon/            # Background BLE service
├── native/            # Native layer (if any)
├── FOTA NEW.java      # Core FOTA logic
└── build.gradle       # Gradle build config

---

## 👩‍💻 Author

**Nandini Malviya**  
[LinkedIn](https://www.linkedin.com/in/nandini-malviya-1183aa280) • [GitHub](https://github.com/nandini-1913)
