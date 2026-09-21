from __future__ import annotations

import sys
from pathlib import Path
from urllib.request import Request, urlopen

from telethon import TelegramClient

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.config import Settings
from app.database import Database


def main() -> None:
    settings = Settings.from_env()
    db = Database(settings.database_path)
    db.initialize()
    api_id = db.get_setting("telegram.api_id") or settings.telegram_api_id
    api_hash = db.get_setting("telegram.api_hash") or settings.telegram_api_hash
    session = settings.telegram_session
    if not api_id or not api_hash:
        raise SystemExit("Save your Telegram API ID and API hash in the app first")
    Path(session).parent.mkdir(parents=True, exist_ok=True)
    client = TelegramClient(session, int(api_id), api_hash)
    print("Telegram will ask for your phone number, login code, and possibly 2FA password.")
    client.start()
    print(f"Authorized. Session saved at {session}. Keep it private.")
    client.disconnect()
    db.close()
    try:
        request = Request(
            "http://127.0.0.1:8000/api/telegram/restart", data=b"", method="POST"
        )
        with urlopen(request, timeout=5):
            pass
        print("Telegram listener reconnected.")
    except OSError:
        print("Return to TelegramNotif and tap Save changes to reconnect the listener.")


if __name__ == "__main__":
    main()
