# TelegramNotif Android app

This is the native Android client for the Telegram listing watcher. The Python service in the
repository remains the Telegram/GapGPT backend; the APK connects to that service over HTTP.

## Build locally

Install Android Studio or a JDK plus the Android SDK, then run from this directory:

```bash
gradle :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Build with GitHub Actions

The `Build Android APK` workflow runs on pushes that change `android/` and can also be started from
the Actions tab with **Run workflow**. Download the `TelegramNotif-debug-apk` artifact after the
run completes.

## First launch

1. Either start the Python backend on a server reachable by the phone, or install Termux on the
   same phone and clone this repository to `/data/data/com.termux/files/home/TelegramNotif`.
2. For Termux-on-phone mode, configure `.env` and complete `python scripts/auth_telegram.py` once
   in Termux. In Termux settings, enable external app commands by setting
   `allow-external-apps=true` in `~/.termux/termux.properties`, then restart Termux.
3. Enter the backend URL in the app. Use `http://127.0.0.1:8000` for Termux on the same phone;
   for the Android emulator's computer, use `http://10.0.2.2:8000`; for a physical phone and a
   computer backend, use the computer's LAN address or an HTTPS URL.
4. Enable **Start the Termux backend automatically on app launch** if you want the APK to send the
   startup command to Termux every time it opens. The script installs/checks Python dependencies,
   acquires a wake lock when available, and starts Uvicorn without opening a terminal window.
5. Enter the GapGPT key and text/vision model IDs, save, and test the connection.
6. Join private Telegram channels with the account used by the backend session. Use **Find channels
   I joined** or add a numeric `-100...` channel ID.
7. Tap **Start alerts**. The app uses a foreground service to poll unread notifications every 15
   seconds and shows high-importance local notifications, including the first album image. All album
   images are shown in the in-app detail view.

The service can restart after reboot when it has been enabled. Android still controls notification
permission, sound, alarm, lock-screen, and Do Not Disturb behavior; an APK cannot silently override
those OS policies. Termux itself must also be exempted from battery optimization if the phone is
expected to keep the Python process alive for long periods.
