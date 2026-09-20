from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv
from telethon import TelegramClient


def main() -> None:
    load_dotenv()
    api_id = os.getenv("TELEGRAM_API_ID")
    api_hash = os.getenv("TELEGRAM_API_HASH")
    session = os.getenv("TELEGRAM_SESSION", "data/telegram")
    if not api_id or not api_hash:
        raise SystemExit("Set TELEGRAM_API_ID and TELEGRAM_API_HASH in .env first")
    Path(session).parent.mkdir(parents=True, exist_ok=True)
    client = TelegramClient(session, int(api_id), api_hash)
    print("Telegram will ask for your phone number, login code, and possibly 2FA password.")
    client.start()
    print(f"Authorized. Session saved at {session}. Keep it private.")
    client.disconnect()


if __name__ == "__main__":
    main()
