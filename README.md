# SMS to Slack

**Automatically forward incoming SMS messages to your Slack channel.**

No server required. No account needed. Just paste a Slack Incoming Webhook URL and every SMS your phone receives will be instantly posted to Slack.

---

## How It Works

When your phone receives an SMS, this app posts it to your chosen Slack channel using the [Incoming Webhooks API](https://api.slack.com/messaging/webhooks):

```
📱 New SMS from +886912345678

Your verification code is 1234.

To: +886987654321 · 2026-04-11T08:30:00Z · via Pixel 7
```

---

## Features

- **Zero configuration** — just a Slack webhook URL and you're done
- **Instant delivery** — forwards in the background as soon as the SMS arrives
- **Multi-device** — set a custom device name so you know which phone sent it
- **Test before going live** — send a test message or pick a real SMS from your inbox to preview the Slack output
- **Forwarding log** — see a history of every message sent
- **8 languages** — English, 繁體中文, 简体中文, 日本語, 한국어, Español, Français, Deutsch

---

## Setup (2 minutes)

1. Go to [api.slack.com/apps](https://api.slack.com/apps) → **Create New App** → From scratch
2. Name your app, select your workspace → **Create App**
3. Click **Incoming Webhooks** in the sidebar → toggle **ON**
4. Click **Add New Webhook to Workspace** → pick a channel → **Allow**
5. Copy the webhook URL (`https://hooks.slack.com/services/…`)
6. Paste it into SMS to Slack → **Save Settings**

---

## Download

> *Coming soon on Google Play*

Or build from source:

```bash
git clone https://github.com/yhlhenry/sms-to-slack.git
cd sms-to-slack
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## Permissions

| Permission | Why |
|---|---|
| `RECEIVE_SMS` | Detect and read incoming SMS messages |
| `READ_SMS` | Test with real messages from your inbox |
| `READ_PHONE_STATE` / `READ_PHONE_NUMBERS` | Read your own phone number for the "To:" field |
| `INTERNET` | Send HTTP requests to Slack |
| `READ_CONTACTS` | Display contact names in the SMS picker (optional) |

All data goes **directly from your device to your Slack webhook**. The developer has zero access to your messages.

---

## Privacy Policy

See [PRIVACY.md](PRIVACY.md) or open an issue for questions.

---

## Issues & Feedback

Found a bug or have a feature request? [Open an issue](https://github.com/yhlhenry/sms-to-slack/issues) — all feedback is welcome.

---

## License

MIT
