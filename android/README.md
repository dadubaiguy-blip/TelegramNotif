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
run completes. Starting with version 0.4.0, GitHub Actions keeps a protected signing-key cache and
refreshes it on a schedule so later APKs install over this stable baseline. Builds older than 0.4.0
used temporary runner keys and may require one uninstall before installing 0.4.0.

## First launch

1. Either start the Python backend on a server reachable by the phone, or install Termux on the
   same phone. The repository must be public for automatic first-time setup.
2. For Termux-on-phone mode, enable external app commands by setting
   `allow-external-apps=true` in `~/.termux/termux.properties`, then restart Termux.
   Open TelegramNotif and use the small gear icon to enable **Start and update through Termux**.
   The app installs Git if needed, clones this repository to
   `/data/data/com.termux/files/home/TelegramNotif`, and updates it on future launches.
   A setup window displays live logs, estimated percentage and time remaining, and download speed
   whenever the command-line downloader reports one.
3. Start the local backend once, then open the gear menu. Under **Telegram account**, enter your own
   API ID and API hash from `my.telegram.org`, enable channel watching, and tap
   **Save credentials and log in**. Enter your phone number, Telegram code, and optional 2FA password
   in the Termux window. The app saves the settings and reconnects the listener after login, so
   editing `.env` is not required. The session and `data/` folder are not overwritten by updates.
4. Enter the backend URL in Settings. Use `http://127.0.0.1:8000` for Termux on the same phone;
   for the Android emulator's computer, use `http://10.0.2.2:8000`; for a physical phone and a
   computer backend, use the computer's LAN address or an HTTPS URL.
5. Enter the GapGPT base URL, key, text/vision model IDs, image-analysis toggle, and timeout in the
   gear menu, then save and test the connection.
6. Join private Telegram channels with the same account used for the backend session. The complete
   numeric `-100...` channel ID is enough after the account has joined. Use **Find joined channels**
   or paste that numeric ID into the channel field.
7. Tap **Start alerts**. The app uses a foreground service to poll unread notifications every 15
   seconds and shows high-importance local notifications, including the first album image. Only
   messages received after the Telegram listener starts are processed. Only priced game listings
   notify; accounts, giveaways, contests, chatter, and unrelated posts are blocked. All album images
   are shown in the in-app detail view.
8. Add game names under **Alarm watchlist**. English/Persian aliases and Persian/Arabic digits are
   normalized, so an `FC 27` rule also matches forms such as `FC27` and `اف سی ۲۷ التیمیت`, whether
   found in text or by the selected vision model. Use the two Android special-access buttons if you
   want urgent matches to sound through DND and open full-screen while the device is locked.

## Telegram Web beta mode

If `my.telegram.org` will not issue an API ID/hash, open the gear menu, enable **Telegram Web beta**,
and tap **Open Telegram Web login**. Log in on the official Telegram Web page shown inside the app,
then tap **Done** and start alerts. The login remains in app-private WebView storage after the app UI
closes or monitoring is stopped. Android cannot reuse an existing Chrome login.

The background foreground-service rotates through configured channels and captures newly visible
message text and up to four visible images. Its first successful scan establishes a baseline, so old
messages do not notify. Telegram Web is a fallback rather than a supported message API: page changes,
Android process limits, unloaded media, or channels that cannot be opened from their configured
username/ID can cause missed information.

Use **Stop all** in the main screen, **Stop everything** in Settings, or the foreground notification
action to stop Android monitoring and the Termux backend while retaining the web login. Use
**Sign out and erase saved web login** to delete cookies, local web storage, and the scan baseline.

The backend suppresses repeat alerts using a persistent content fingerprint across all configured
channels. If a new caption with price/details replies to an image or album, the backend downloads
the referenced full album and analyzes it together with that new reply.

The service can restart after reboot when it has been enabled. Android still controls notification
permission, sound, alarm, lock-screen, and Do Not Disturb behavior; an APK cannot silently override
those OS policies. Termux itself must also be exempted from battery optimization if the phone is
expected to keep the Python process alive for long periods.
