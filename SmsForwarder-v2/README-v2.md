# SMS Forwarder v2 — Android 10+ only

[![Android](https://img.shields.io/badge/Android-10%2B%20(API%2029%2B)-brightgreen)]()
[![Release](https://img.shields.io/github/v/tag/toofanusir-svg/SmsHostForwarder?filter=v2-*&label=v2%20release)](https://github.com/toofanusir-svg/SmsHostForwarder/releases)

A standalone build of SMS Forwarder targeting **Android 10+ (API 29)** only.
The original project (`app/`) is untouched.

---

## Why v2 exists

The original APK works fine on Android 8-9. On Android 10+ — especially Xiaomi HyperOS /
MIUI and Samsung One UI 7 (Android 15) — SMS is silently dropped.

Two root causes:

1. **`RECEIVER_NOT_EXPORTED` on API 33+** — a dynamic receiver registered with
   `RECEIVER_NOT_EXPORTED` never receives `SMS_RECEIVED_ACTION` because the system treats
   it as internal-only. Fixed by using `RECEIVER_EXPORTED`.

2. **Xiaomi HyperOS background process killing** — even with `RECEIVER_EXPORTED`, HyperOS
   blocks broadcasts to dynamic receivers when the app is backgrounded. A
   *manifest-declared static receiver* bypasses this because the system wakes the process
   itself to deliver the broadcast.

---

## Changes vs the original app

### Code

| File | Change |
|------|--------|
| `SmsService.kt` | `RECEIVER_NOT_EXPORTED` → `RECEIVER_EXPORTED`; removed all `Build.VERSION < 29` branches; `startForegroundService` called directly |
| `SMSReceiver.kt` | Removed deprecated `PhoneStateListener` path (API 29+ uses `TelephonyCallback`); `readMissedCallLog` uses `getColumnIndexOrThrow` + `cursor.use` |
| `StaticSmsReceiver.kt` | **New** — manifest-declared receiver for Xiaomi HyperOS; wakes the process and starts `SmsService` if not running |
| `StartupReceiver.kt` | Added `QUICKBOOT_POWERON` (Xiaomi fast-boot) and `com.htc.intent.action.QUICKBOOT_POWERON` |
| `MainActivity.kt` | Added `checkXiaomiAutostart()` — detects Xiaomi/Redmi/POCO and shows a one-time dialog guiding the user to Security → Autostart; added `checkBatteryOptimization()` |

### Manifest

```xml
<!-- Static receiver — bypasses Xiaomi HyperOS background restrictions -->
<receiver
    android:name=".StaticSmsReceiver"
    android:exported="true"
    android:permission="android.permission.BROADCAST_SMS">
    <intent-filter android:priority="999">
        <action android:name="android.provider.Telephony.SMS_RECEIVED" />
    </intent-filter>
</receiver>

<!-- Boot + Xiaomi fast-boot -->
<receiver android:name=".StartupReceiver" android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
</receiver>
```

New permission:
```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

### Build

| Property | Original | v2 |
|----------|----------|----|
| `applicationId` | `com.example.smsfrowarder` | `com.example.smsforwarder.v2` |
| `minSdk` | 23 (Android 6) | 29 (Android 10) |
| `versionName` | 4.9.x | 1.0.x |

Both APKs can be installed side-by-side on the same device.

---

## Download

Download `SmsForwarder-v2-x.x.x.apk` from the
[Releases page](https://github.com/toofanusir-svg/SmsHostForwarder/releases)
(releases tagged `v2-*`).

---

## Setup on Xiaomi / HyperOS after install

The app shows a one-time dialog on first launch. If you missed it:

1. **Security app → Permissions → Autostart** → enable SMS Forwarder
2. **Settings → Apps → SMS Forwarder → Battery → No restrictions**
3. **Settings → Apps → SMS Forwarder → Permissions** — confirm SMS and Phone are granted
4. Tap **Start** in the app

---

## Tested on

| Device | OS | Result |
|--------|----|--------|
| Samsung Galaxy S7 | Android 8.0 | ✅ original APK |
| Samsung Galaxy (One UI 7) | Android 15 | ✅ v2 APK |
| Xiaomi 12 Pro | HyperOS 3.0.4 / Android 15 | ✅ v2 APK (after Autostart) |
