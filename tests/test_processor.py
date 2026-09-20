from pathlib import Path

from app.ai import GapGPTClient
from app.config import Settings
from app.database import Database
from app.notifications import NotificationHub
from app.processor import MessageProcessor


async def test_processor_persists_and_notifies_without_ai(tmp_path: Path):
    settings = Settings(
        database_path=tmp_path / "app.db",
        media_dir=tmp_path / "media",
        telegram_api_id=None,
        telegram_api_hash=None,
        telegram_session=str(tmp_path / "telegram"),
        gapgpt_base_url=None,
        gapgpt_api_key=None,
        gapgpt_model=None,
        gapgpt_vision_model=None,
        ai_enable_vision=False,
        notify_only_with_price=False,
        ai_timeout_seconds=2,
        start_telegram=False,
    )
    settings.ensure_directories()
    db = Database(settings.database_path)
    db.initialize()
    channel = db.get_channel_by_source("XCrack_Land0")
    assert channel is not None
    db.upsert_watchlist("GTA 6")
    processor = MessageProcessor(
        db, GapGPTClient(settings, db), NotificationHub(), settings.media_dir
    )

    event = await processor.process(
        channel=channel,
        telegram_message_id=123,
        text="GTA 6\nPrice: $50\nDM @seller",
    )

    assert event is not None
    assert event["urgent"] is True
    messages = db.list_messages()
    assert len(messages) == 1
    assert db.parse_json(messages[0]["parsed_json"])["item_names"] == ["GTA 6"]
    assert len(db.list_notifications()) == 1
    db.close()


async def test_missing_price_still_notifies_by_default(tmp_path: Path):
    settings = Settings(
        database_path=tmp_path / "app.db",
        media_dir=tmp_path / "media",
        telegram_api_id=None,
        telegram_api_hash=None,
        telegram_session=str(tmp_path / "telegram"),
        gapgpt_base_url=None,
        gapgpt_api_key=None,
        gapgpt_model=None,
        gapgpt_vision_model=None,
        ai_enable_vision=False,
        notify_only_with_price=False,
        ai_timeout_seconds=2,
        start_telegram=False,
    )
    settings.ensure_directories()
    db = Database(settings.database_path)
    db.initialize()
    channel = db.get_channel_by_source("XCrack_Land0")
    assert channel is not None
    processor = MessageProcessor(
        db, GapGPTClient(settings, db), NotificationHub(), settings.media_dir
    )

    event = await processor.process(
        channel=channel,
        telegram_message_id=124,
        text="GTA 6\nDM @seller",
    )

    assert event is not None
    assert "Price not listed" in event["body"]
    db.close()


async def test_album_creates_one_notification_with_all_images(tmp_path: Path):
    settings = Settings(
        database_path=tmp_path / "app.db",
        media_dir=tmp_path / "media",
        telegram_api_id=None,
        telegram_api_hash=None,
        telegram_session=str(tmp_path / "telegram"),
        gapgpt_base_url=None,
        gapgpt_api_key=None,
        gapgpt_model=None,
        gapgpt_vision_model=None,
        ai_enable_vision=False,
        notify_only_with_price=False,
        ai_timeout_seconds=2,
        start_telegram=False,
    )
    settings.ensure_directories()
    (settings.media_dir / "album-1.jpg").write_bytes(b"image one")
    (settings.media_dir / "album-2.jpg").write_bytes(b"image two")
    db = Database(settings.database_path)
    db.initialize()
    channel = db.get_channel_by_source("XCrack_Land0")
    assert channel is not None
    processor = MessageProcessor(
        db, GapGPTClient(settings, db), NotificationHub(), settings.media_dir
    )

    event = await processor.process_album(
        channel=channel,
        album_id="123:9001",
        items=[
            {
                "telegram_message_id": 200,
                "text": "GTA 6",
                "media_path": "album-1.jpg",
                "media_mime": "image/jpeg",
            },
            {
                "telegram_message_id": 201,
                "text": "Price: $50 DM @seller",
                "media_path": "album-2.jpg",
                "media_mime": "image/jpeg",
            },
        ],
    )

    assert event is not None
    assert event["message"]["album_count"] == 2
    assert len(event["message"]["media_urls"]) == 2
    assert len(db.list_notifications()) == 1
    assert len(db.list_album_messages("123:9001")) == 2
    db.close()
