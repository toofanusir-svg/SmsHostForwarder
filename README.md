# SMS Forwarder

An Android app that forwards incoming SMS and call information to your Telegram Bot and/or custom Webhook — with rule-based filtering by SIM card and sender number.

[![GitHub release](https://img.shields.io/github/v/release/toofanusir-svg/SmsHostForwarder)](https://github.com/toofanusir-svg/SmsHostForwarder/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-orange)]()

---

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [How It Works](#how-it-works)
- [Creating a Telegram Bot](#creating-a-telegram-bot)
- [Finding Your Chat ID](#finding-your-chat-id)
- [Creating a Rule](#creating-a-rule)
- [Webhook Setup](#webhook-setup)
- [Testing Rules](#testing-rules)
- [Forward Log](#forward-log)
- [Settings (Language & Theme)](#settings-language--theme)
- [Background Service & Auto-Start](#background-service--auto-start)
- [Troubleshooting](#troubleshooting)
- [Security](#security)
- [Building from Source](#building-from-source)
- [License](#license)

---

## Features

- **Rule-based forwarding** — create multiple rules, each with its own SIM filter, sender filter, and destination
- **Dual destination** — each rule can send to Telegram, Webhook, or both simultaneously
- **SIM filtering** — forward from SIM 1, SIM 2, or any SIM
- **Sender filtering** — forward from a specific number or all senders
- **Forward log** — see exactly what was sent, when, and whether it succeeded (✅) or failed (❌)
- **Retry failed sends** — tap Retry on any failed log entry to re-attempt
- **Per-rule Test button** — test a rule's destination before saving
- **Global Test button** — test all rules at once from the main screen
- **Enable/disable rules** — toggle rules on/off without deleting them
- **Farsi (فارسی) and English** language support
- **Light and Dark theme**
- **Background service** — runs continuously, survives device reboot
- **Android 8.0+ support** (API 26+)

## Requirements

- Android 8.0 (API 26) or higher
- A phone that can receive SMS and calls
- Internet access
- A Telegram Bot token (free, takes 2 minutes to create)

## Installation

1. Go to the [Releases page](https://github.com/toofanusir-svg/SmsHostForwarder/releases)
2. Download the latest `SmsForwarder-x.x.x.apk`
3. Open the file on your phone (allow "Install from unknown sources" if prompted)
4. Open the app

## How It Works

```
SMS/Call arrives → App checks ALL enabled rules → Each matching rule fires independently
                                                                ↓
                                              ┌─────────────────┴─────────────────┐
                                              ↓                                   ↓
                                    Telegram Bot API                     Custom Webhook URL
```

All matching rules are checked and fired **in parallel** — no rule blocks another.

## Creating a Telegram Bot

1. Open Telegram and search for **@BotFather**
2. Send `/newbot`
3. Choose a name (e.g. `My SMS Forwarder`)
4. Choose a username ending in `bot` (e.g. `my_sms_forwarder_bot`)
5. BotFather replies with a **HTTP API token** like:
   ```
   7123456789:AAH-abcdefGHIjklmNOPqrstUVWxyz
   ```
6. Copy this token — you'll paste it into the app

## Finding Your Chat ID

The bot cannot write to you until you write to it first. You need your numeric Chat ID:

1. Send any message (e.g. `/start`) to your new bot in Telegram
2. Open this URL in your browser (replace `TOKEN` with your bot token):
   ```
   https://api.telegram.org/bot<TOKEN>/getUpdates
   ```
3. Look for `"chat":{"id":XXXXXXXXX}` in the response — that number is your Chat ID

> **Tip:** You can forward to multiple chats by entering multiple Chat IDs separated by commas: `73638059,98765432`

## Creating a Rule

1. Open the app — tap the **+** (FAB) button
2. **Rule name** — give it a label (e.g. "Bank SMS")
3. **SIM card** — choose Any SIM, SIM 1, or SIM 2
4. **Sender number** — enter a specific number, or `*` for all senders
5. **Telegram Bot** — check the box and fill in:
   - **Bot token** — from BotFather (step above)
   - **Chat IDs** — from the step above (comma-separated for multiple)
6. **Webhook** — optionally check this box and fill in:
   - **Webhook URL** — your server endpoint (must start with `https://` or `http://`)
   - **Authorization header** — optional, sent as `Authorization: <value>`
7. Tap **Test** to verify the destination works before saving
8. Tap **Save**

### Example Rules

| Rule Name | SIM | Sender | Telegram | Webhook |
|-----------|-----|--------|----------|---------|
| All SMS | Any | `*` | token + chat ID | — |
| Bank alerts | Any | `+9812345678` | token + chat ID | — |
| SIM1 → Telegram | SIM 1 | `*` | token + chat ID | — |
| SIM2 → Server | SIM 2 | `*` | — | `https://myserver.com/sms` |
| Both destinations | Any | `+9812345678` | token + chat ID | `https://myserver.com/sms` |

## Webhook Setup

When a webhook is configured, the app sends an HTTP POST to your URL with this JSON body:

```json
{
  "message": "📱 *SIM 1* — From *+9812345678* at Wed Jan 15 14:30:00 GMT 2025\n\nYour verification code is `1234`"
}
```

A minimal server to receive it (Node.js / Express example):

```javascript
const express = require('express');
const app = express();
app.use(express.json());

app.post('/sms', (req, res) => {
    console.log('Received:', req.body.message);
    res.json({ ok: true });
});

app.listen(3000, () => console.log('Webhook server running on :3000'));
```

If you set an Authorization header (e.g. `Bearer my-secret-token`), the app sends it as:
```
Authorization: Bearer my-secret-token
```

## Testing Rules

### Per-rule test (in edit screen)
While creating or editing a rule, tap the **Test** button. This sends a test message using the current form values **without saving**. A dialog shows ✅ or ❌ for each destination.

### Global test (main screen)
Tap the **Test** button on the main screen. This sends a test message through **all enabled rules** and shows a summary: how many succeeded, how many failed.

## Forward Log

Tap the **Log** icon in the toolbar to see the forwarding history:

- ✅ Green = sent successfully
- ❌ Red = failed (shows error message on tap)
- ⏳ = in progress

For failed entries, a **Retry** button appears — tap it to re-attempt the send.

The log stores the last 500 entries. Tap **Clear Log** to wipe the history.

## Settings (Language & Theme)

Tap the **gear icon** in the toolbar:

- **Language** — English or فارسی (Persian)
- **Theme** — Light or Dark

Changes apply immediately. The app restarts to apply the new language.

**Defaults:** English, Light theme.

## Background Service & Auto-Start

- Tap **Start** on the main screen to start the forwarding service
- The service runs as a **foreground service** with a persistent notification (required by Android)
- The service is **sticky** — Android restarts it if killed
- On **device reboot**, the service starts automatically (via `BOOT_COMPLETED` receiver)
- Tap **Stop** to stop the service manually

## Troubleshooting

### SMS not forwarding
- Verify the service is running (Start button should be disabled)
- Check the **Log** page for errors
- Verify your bot token is correct (use the Test button)
- Make sure you sent `/start` to your bot first (Telegram requires this)

### "SIM-specific rules will not match" warning
Your device does not support multi-SIM detection (common on older devices). Use "Any SIM" for rules.

### App crashes
The app shows a crash screen with the stack trace. Tap **Copy** and send the report for debugging.

### Webhook not working
- Verify the URL starts with `https://` or `http://`
- Check the Log for the HTTP error code
- Test with the per-rule Test button

## Security

- The app uses **your personal Telegram bot token** — data goes directly to Telegram's servers
- No data is sent to any third-party server (except your configured webhook)
- Rule data (tokens, URLs) is stored in the app's private storage (`filesDir`, not accessible by other apps)
- `android:allowBackup="false"` — data is not included in system backups
- SSL/TLS certificate validation is enabled (no "trust all" shortcuts)

## Building from Source

```bash
git clone https://github.com/toofanusir-svg/SmsHostForwarder.git
cd SmsHostForwarder
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

GitHub Actions automatically builds and publishes APKs on every tagged release.

## License

This is a pet project. Use it freely at your own risk.
