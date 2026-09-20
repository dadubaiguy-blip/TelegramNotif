from pathlib import Path

from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


def make_client(tmp_path: Path) -> TestClient:
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
    return TestClient(create_app(settings))


def test_seeded_channels_and_settings(tmp_path: Path):
    with make_client(tmp_path) as client:
        assert client.get("/").status_code == 200
        assert "Telegram Watcher" in client.get("/").text
        response = client.get("/api/channels")
        assert response.status_code == 200
        assert [item["source"] for item in response.json()] == [
            "XCrack_Land0",
            "theonlymoonofxbox",
            "MoonOFxbox2",
            "xcrvckk",
            "centraljdj",
        ]
        settings = client.get("/api/settings")
        assert settings.status_code == 200
        assert settings.json()["ai"]["api_key_set"] is False
        assert settings.json()["notifications"]["click_target"] == "app"


def test_watchlist_round_trip(tmp_path: Path):
    with make_client(tmp_path) as client:
        created = client.post("/api/watchlist", json={"name": "GTA 6"})
        assert created.status_code == 201
        item_id = created.json()["id"]
        assert client.get("/api/watchlist").json()[0]["name"] == "GTA 6"
        assert client.delete(f"/api/watchlist/{item_id}").status_code == 204


def test_notification_settings_round_trip(tmp_path: Path):
    with make_client(tmp_path) as client:
        response = client.put(
            "/api/settings/notifications",
            json={"only_notify_with_price": True, "click_target": "telegram"},
        )
        assert response.status_code == 200
        assert response.json() == {"only_notify_with_price": True, "click_target": "telegram"}
