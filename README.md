# 📞 Call Recorder Service (Android 14+ Compatible)  its behavior can vary a lot depending on the device and Android version.

A robust Android Call Recorder using `AccessibilityService` and `MediaRecorder` APIs, compatible with **Android 9 to Android 14+**. This app records incoming and outgoing calls and stores them as `.m4a` files in the Music directory. 

✅ **Tested and working on Android 14 (API 34)**  
🔒 Follows modern privacy and storage policies (Scoped Storage for Android 10+)  
---

## 🚀 Features

- 📲 Automatically records incoming & outgoing calls
- 💾 Saves recordings in Music/CallRecordings (`MediaStore` or `File`)
- 🔊 Uses `MIC` audio source for higher volume
- 🛡️ WakeLock ensures recording runs uninterrupted
- ⚡ Post-call file size and integrity validation
- ☑️ Fully tested on Android 14
- 🎯 Backward-compatible with Android 9+

---

## 📂 Output File Format

- Format: `.m4a`
- Directory (Android 10+): `Music/CallRecordings/`
- Naming: `Call_yyyyMMdd_HHmmss_abcd.m4a`

---

## ⚙️ How it Works

The service listens to phone state changes via `PhoneStateListener`:

| Call State | Action |
|------------|--------|
| `CALL_STATE_OFFHOOK` | Starts recording (with delay) |
| `CALL_STATE_IDLE` | Stops recording |
| `CALL_STATE_RINGING` | Logs the ringing event |

Recording is managed by a `MediaRecorder` instance running in a background thread with a partial WakeLock.

---

## ✅ Requirements

- Android 9 (API 28) to Android 14 (API 34)
- **Microphone permission**
- **AccessibilityService enabled** manually by user
- Foreground or background service permission for Android 10+

---

## 🛠 Permissions Used

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.READ_CALL_LOG" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
