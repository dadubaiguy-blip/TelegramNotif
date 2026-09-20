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

1. Start the Python backend on a server reachable by the phone.
2. Enter its URL in the app. For the Android emulator, the local computer is `http://10.0.2.2:8000`;
   for a physical phone, use the computer's LAN address or an HTTPS URL.
3. Enter the GapGPT key and text/vision model IDs, save, and test the connection.
4. Join private Telegram channels with the account used by the backend session. Use **Find channels
   I joined** or add a numeric `-100...` channel ID.
5. Tap **Start alerts**. The app uses a foreground service to poll unread notifications every 15
   seconds and shows high-importance local notifications, including the first album image. All album
   images are shown in the in-app detail view.

The service can restart after reboot when it has been enabled. Android still controls notification
permission, sound, alarm, lock-screen, and Do Not Disturb behavior; an APK cannot silently override
those OS policies.
