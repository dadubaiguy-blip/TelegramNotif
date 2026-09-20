# Telegram Listing Watcher Backend

This is a small local web app for a Telegram listing watcher. It watches configured channels,
stores new messages and images, extracts listing fields with a configurable GapGPT/OpenAI-compatible
API, matches a personal watchlist, and emits notifications over REST and WebSocket.

The five requested public sources are seeded automatically:

- `XCrack_Land0`
- `theonlymoonofxbox`
- `MoonOFxbox2`
- `xcrvckk`
- `centraljdj`

## Run locally

```bash
python -m venv .venv
source .venv/bin/activate       # Windows: .venv\\Scripts\\activate
pip install -e ".[dev]"
cp .env.example .env
# Fill in Telegram and GapGPT values in .env when ready.
uvicorn app.main:app --reload
```

Open the app at `http://127.0.0.1:8000/`. OpenAPI is available at `http://127.0.0.1:8000/docs`.

## Android APK

The native Android client lives in `android/`. It connects to this backend, keeps a foreground
monitor running while the phone sleeps, and displays local high-importance notifications with the
first album image. The in-app detail view contains the full album gallery and can open the original
Telegram message.

The GitHub Actions workflow at `.github/workflows/android-apk.yml` builds a debug APK on changes to
`android/` or when started manually. Download the `TelegramNotif-debug-apk` artifact from the
workflow run. See [`android/README.md`](android/README.md) for setup and phone/emulator URLs.

The APK can optionally run the backend locally through Termux on the same phone. When this public
repository is not present, the app clones it into `$HOME/TelegramNotif`; on later app launches it
fast-forwards the `main` branch, refreshes Python dependencies when needed, and restarts the backend
only when the revision changed. Local `.env`, Telegram session, database, and media files are kept.
Termux and the one-time Telegram login still need to be installed/configured by the user; Android
does not allow the APK to silently install another app or complete an account login.

## Telegram setup

1. Create Telegram API credentials at `https://my.telegram.org` and put `TELEGRAM_API_ID` and
   `TELEGRAM_API_HASH` in `.env`.
2. Log in once with the user account that can see the channels:

   ```bash
   python scripts/auth_telegram.py
   ```

3. Start the API. The listener uses the saved user session and begins watching the seeded sources.

For the two private channels, join them in Telegram using the account behind the session first, then
add their numeric Telegram chat IDs (or a username if the private channel has one):

```bash
curl -X POST http://127.0.0.1:8000/api/channels \
  -H 'content-type: application/json' \
  -d '{"source":"-1001234567890","display_name":"Private channel 1"}'
```

The app's **Settings → Private channels → Find channels I joined** button lists channels visible to
the authorized Telegram account. Select either private channel there. The API reloads the listener
after adding or changing a channel. It does not auto-join invite links.
Keep `data/telegram.session` private; it represents a logged-in Telegram session.

## GapGPT setup

The adapter calls an OpenAI-compatible `/chat/completions` endpoint. Set the exact base URL, API key,
and model supplied by the GapGPT account in `.env`, for example:

```dotenv
GAPGPT_BASE_URL=https://your-gapgpt-endpoint.example/v1
GAPGPT_API_KEY=replace-me
GAPGPT_MODEL=replace-me
GAPGPT_VISION_MODEL=replace-me
AI_ENABLE_VISION=true
```

The app has separate text and vision model fields. When vision is enabled, each image or album is
sent to the selected vision model first; its description is then given to the text model so the
same structured extractor can identify games, prices, availability, and contacts.

The app also exposes settings endpoints:

- `GET /api/settings`
- `PUT /api/settings/ai` with `{ "api_key": "...", "base_url": "...", "model": "..." }`
- `GET /api/ai/models`
- `POST /api/ai/test`
- `PUT /api/settings/notifications` with `click_target: "app" | "telegram"`

Keys entered through the settings API are stored in the local SQLite database and never returned by
the API. For a deployed service, use environment variables or a proper secrets manager and add
authentication before exposing these endpoints.

If AI is not configured or the provider fails, the worker still stores the message and uses a small
English/Persian heuristic parser. A provider error is retained as `processing_error` on the message.
Image understanding is opt-in with `AI_ENABLE_VISION=true`; images are still stored and exposed to
the app even when vision is off. Telegram albums are buffered briefly and become one notification
with one combined text payload and an image gallery. Individual non-album images are supported too.

Messages without prices still notify by default and display `Price not listed`. To skip them instead,
set `NOTIFY_ONLY_WITH_PRICE=true` or enable the switch in Settings.

## Frontend contract

Useful routes for the next frontend pass:

- `GET /api/messages?category=game|account|other&urgent=true`
- `GET /api/messages/{id}`
- `GET /api/messages/{id}/media`
- `GET /api/notifications?unread_only=true`
- `POST /api/notifications/{id}/read`
- `GET /api/telegram/dialogs`
- `GET/POST/DELETE /api/watchlist`
- `GET /api/channels` and `POST/PATCH/DELETE /api/channels/{id}`
- `WS /ws/notifications`

Each WebSocket event has the shape `{ "type": "new_notification", "notification": { ... } }` and
includes the structured listing, image URLs, and a `telegram_url`. Notification settings choose
whether a frontend should open the in-app detail view or the Telegram message. The in-app detail
view always includes an `Open in Telegram` action.

The `/api/devices` endpoint stores future mobile push targets, but FCM/APNs delivery is intentionally
left to the native/mobile frontend choice. A backend can mark a notification urgent, but it cannot
force a phone to bypass DND, mute, sleep, or lock-screen policy. Android/iOS must grant the relevant
notification, sound, alarm, and (where supported) full-screen permissions, and the app must respect
platform policy.

## Tests

```bash
pytest
```
