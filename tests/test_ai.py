from pathlib import Path

from app.ai import GapGPTClient
from app.config import Settings
from app.database import Database


async def test_vision_model_feeds_text_model(tmp_path: Path):
    settings = Settings(
        database_path=tmp_path / "app.db",
        media_dir=tmp_path / "media",
        telegram_api_id=None,
        telegram_api_hash=None,
        telegram_session=str(tmp_path / "telegram"),
        gapgpt_base_url="https://gapgpt.example/v1",
        gapgpt_api_key="test-key",
        gapgpt_model="text-model",
        gapgpt_vision_model="vision-model",
        ai_enable_vision=True,
        notify_only_with_price=False,
        ai_timeout_seconds=2,
        start_telegram=False,
    )
    settings.ensure_directories()
    image = settings.media_dir / "listing.jpg"
    image.write_bytes(b"fake image")
    db = Database(settings.database_path)
    db.initialize()
    client = GapGPTClient(settings, db)
    calls: list[dict] = []

    async def fake_post(payload: dict) -> dict:
        calls.append(payload)
        if payload["model"] == "vision-model":
            return {"choices": [{"message": {"content": "Visible text: GTA 6, price $50"}}]}
        return {
            "choices": [
                {
                    "message": {
                        "content": '{"category":"game","item_names":["GTA 6"],"price":"50","currency":"$","availability":"available","contact_handles":[],"summary":"GTA 6","confidence":0.9}',
                    }
                }
            ]
        }

    client._post_chat = fake_post  # type: ignore[method-assign]
    parsed, error = await client.extract("DM @seller", image_paths=[image])

    assert error is None
    assert parsed["item_names"] == ["GTA 6"]
    assert [call["model"] for call in calls] == ["vision-model", "text-model"]
    assert "Image analysis from the vision model" in calls[1]["messages"][1]["content"]
    db.close()
