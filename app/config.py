from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

DEFAULT_CHANNELS = (
    "XCrack_Land0",
    "theonlymoonofxbox",
    "MoonOFxbox2",
    "xcrvckk",
    "centraljdj",
)


def _bool_env(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _int_env(name: str) -> int | None:
    value = os.getenv(name, "").strip()
    if not value:
        return None
    try:
        return int(value)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc


@dataclass(slots=True)
class Settings:
    database_path: Path
    media_dir: Path
    telegram_api_id: int | None
    telegram_api_hash: str | None
    telegram_session: str
    gapgpt_base_url: str | None
    gapgpt_api_key: str | None
    gapgpt_model: str | None
    gapgpt_vision_model: str | None
    ai_enable_vision: bool
    notify_only_with_price: bool
    ai_timeout_seconds: float
    start_telegram: bool

    @classmethod
    def from_env(cls) -> Settings:
        load_dotenv()
        database_path = Path(os.getenv("DATABASE_PATH", "data/app.db"))
        media_dir = Path(os.getenv("MEDIA_DIR", "data/media"))
        return cls(
            database_path=database_path,
            media_dir=media_dir,
            telegram_api_id=_int_env("TELEGRAM_API_ID"),
            telegram_api_hash=os.getenv("TELEGRAM_API_HASH") or None,
            telegram_session=os.getenv("TELEGRAM_SESSION", "data/telegram"),
            gapgpt_base_url=os.getenv("GAPGPT_BASE_URL") or None,
            gapgpt_api_key=os.getenv("GAPGPT_API_KEY") or None,
            gapgpt_model=os.getenv("GAPGPT_MODEL") or None,
            gapgpt_vision_model=os.getenv("GAPGPT_VISION_MODEL") or None,
            ai_enable_vision=_bool_env("AI_ENABLE_VISION", False),
            notify_only_with_price=_bool_env("NOTIFY_ONLY_WITH_PRICE", True),
            ai_timeout_seconds=float(os.getenv("AI_TIMEOUT_SECONDS", "45")),
            start_telegram=_bool_env("START_TELEGRAM", True),
        )

    def ensure_directories(self) -> None:
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        self.media_dir.mkdir(parents=True, exist_ok=True)
