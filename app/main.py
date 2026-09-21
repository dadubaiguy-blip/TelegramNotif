from __future__ import annotations

import asyncio
import base64
import binascii
import hashlib
import logging
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .ai import AIConfigurationError, GapGPTClient
from .config import Settings
from .database import Database
from .notifications import NotificationHub
from .processor import MessageProcessor
from .schemas import (
    AISettingsUpdate,
    ChannelCreate,
    ChannelUpdate,
    DeviceRegistration,
    NotificationSettingsUpdate,
    TelegramSettingsUpdate,
    WatchlistItem,
    WebMessageIngest,
)
from .telegram_service import TelegramService

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


def _safe_media_path(media_dir: Path, stored_path: str) -> Path:
    candidate = (media_dir / Path(stored_path).name).resolve()
    if candidate.parent != media_dir.resolve():
        raise HTTPException(status_code=404, detail="media not found")
    return candidate


def _web_message_id(source: str, external_id: str, image_index: int = 0) -> int:
    value = f"web:{source}:{external_id}:{image_index}".encode()
    return -int.from_bytes(hashlib.sha256(value).digest()[:7], "big")


def _save_web_media(
    media_dir: Path,
    source: str,
    external_id: str,
    data_urls: list[str],
) -> list[tuple[str, str]]:
    saved: list[tuple[str, str]] = []
    allowed = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}
    for index, data_url in enumerate(data_urls[:4]):
        try:
            header, encoded = data_url.split(",", 1)
            mime = header.removeprefix("data:").split(";", 1)[0].lower()
            extension = allowed.get(mime)
            if not extension:
                continue
            content = base64.b64decode(encoded, validate=True)
            if not content or len(content) > 3 * 1024 * 1024:
                continue
            digest = hashlib.sha256(
                f"{source}:{external_id}:{index}".encode()
            ).hexdigest()[:24]
            filename = f"web_{digest}{extension}"
            (media_dir / filename).write_bytes(content)
            saved.append((filename, mime))
        except (ValueError, binascii.Error, OSError):
            continue
    return saved


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    settings.ensure_directories()
    db = Database(settings.database_path)
    db.initialize()
    hub = NotificationHub()
    ai = GapGPTClient(settings, db)
    processor = MessageProcessor(
        db, ai, hub, settings.media_dir, notify_only_with_price=settings.notify_only_with_price
    )
    telegram = TelegramService(settings, db, processor)

    async def restart_telegram_listener() -> bool:
        task = getattr(app.state, "telegram_task", None)
        if task and not task.done():
            await telegram.stop()
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        if telegram.enabled and telegram.configured:
            app.state.telegram_task = asyncio.create_task(
                telegram.run(), name="telegram-listener"
            )
            return True
        app.state.telegram_task = None
        return False

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        task: asyncio.Task[Any] | None = None
        if telegram.enabled:
            task = asyncio.create_task(telegram.run(), name="telegram-listener")
        app.state.telegram_task = task
        try:
            yield
        finally:
            current_task = getattr(app.state, "telegram_task", None)
            await telegram.stop()
            if current_task:
                current_task.cancel()
                try:
                    await current_task
                except asyncio.CancelledError:
                    pass
            db.close()

    app = FastAPI(
        title="Telegram Listing Watcher API",
        version="0.1.0",
        description="Frontend-ready API for watching Telegram listings and extracting structured data.",
        lifespan=lifespan,
    )
    # The later frontend can be served separately during development. Restrict this list in prod.
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.state.settings = settings
    app.state.db = db
    app.state.ai = ai
    app.state.processor = processor
    app.state.telegram = telegram
    static_dir = Path(__file__).parent / "static"
    app.mount("/static", StaticFiles(directory=static_dir), name="static")

    @app.get("/", include_in_schema=False)
    async def web_app() -> FileResponse:
        return FileResponse(static_dir / "index.html")

    @app.get("/api/health")
    async def health(request: Request) -> dict[str, Any]:
        listener_task = getattr(request.app.state, "telegram_task", None)
        return {
            "ok": True,
            "service": "telegram-listing-watcher",
            "telegram": {
                **telegram.public_settings(),
                "task_running": bool(listener_task and not listener_task.done()),
            },
            "ai": ai.public_settings(),
        }

    @app.get("/api/channels")
    async def list_channels() -> list[dict[str, Any]]:
        return db.list_channels()

    @app.post("/api/channels", status_code=201)
    async def add_channel(payload: ChannelCreate) -> dict[str, Any]:
        channel = db.upsert_channel(payload.source, payload.display_name, payload.enabled)
        if telegram.client and telegram.client.is_connected():
            await telegram.reload_channels()
        return channel

    @app.patch("/api/channels/{channel_id}")
    async def update_channel(channel_id: int, payload: ChannelUpdate) -> dict[str, Any]:
        channel = db.update_channel(channel_id, **payload.model_dump(exclude_unset=True))
        if not channel:
            raise HTTPException(status_code=404, detail="channel not found")
        if telegram.client and telegram.client.is_connected():
            await telegram.reload_channels()
        return channel

    @app.delete("/api/channels/{channel_id}", status_code=204)
    async def delete_channel(channel_id: int) -> None:
        if not db.delete_channel(channel_id):
            raise HTTPException(status_code=404, detail="channel not found")
        if telegram.client and telegram.client.is_connected():
            await telegram.reload_channels()

    @app.get("/api/messages")
    async def list_messages(
        limit: int = Query(default=100, ge=1, le=500),
        offset: int = Query(default=0, ge=0),
        source: str | None = None,
        category: str | None = Query(default=None, pattern="^(game|account|other)$"),
        urgent: bool | None = None,
    ) -> list[dict[str, Any]]:
        rows = db.list_messages(
            limit=limit, offset=offset, source=source, category=category, urgent=urgent
        )
        return [processor.api_message(row) for row in rows]

    @app.get("/api/messages/{message_id}")
    async def get_message(message_id: int) -> dict[str, Any]:
        row = db.get_message(message_id)
        if not row:
            raise HTTPException(status_code=404, detail="message not found")
        return processor.api_message(row)

    @app.get("/api/messages/{message_id}/media")
    async def get_media(message_id: int) -> FileResponse:
        row = db.get_message(message_id)
        if not row or not row.get("media_path"):
            raise HTTPException(status_code=404, detail="media not found")
        path = _safe_media_path(settings.media_dir, str(row["media_path"]))
        if not path.exists():
            raise HTTPException(status_code=404, detail="media not found")
        return FileResponse(path, media_type=row.get("media_mime") or "application/octet-stream")

    @app.get("/api/notifications")
    async def list_notifications(
        limit: int = Query(default=100, ge=1, le=500), unread_only: bool = False
    ) -> list[dict[str, Any]]:
        rows = db.list_notifications(limit=limit, unread_only=unread_only)
        return [processor.api_notification(row) for row in rows]

    @app.post("/api/notifications/{notification_id}/read", status_code=204)
    async def mark_notification_read(notification_id: int) -> None:
        if not db.mark_notification_read(notification_id):
            raise HTTPException(status_code=404, detail="notification not found")

    @app.get("/api/settings")
    async def get_settings() -> dict[str, Any]:
        return {
            "ai": ai.public_settings(),
            "telegram": telegram.public_settings(),
            "watchlist": db.list_watchlist(),
            "notifications": {
                "only_notify_with_price": processor.only_notify_with_price(),
                "click_target": processor.notification_click_target(),
            },
        }

    @app.put("/api/settings/ai")
    async def update_ai_settings(payload: AISettingsUpdate) -> dict[str, Any]:
        return ai.update_settings(**payload.model_dump(exclude_unset=True))

    @app.put("/api/settings/telegram")
    async def update_telegram_settings(payload: TelegramSettingsUpdate) -> dict[str, Any]:
        result = telegram.update_settings(**payload.model_dump(exclude_unset=True))
        result["task_started"] = await restart_telegram_listener()
        return result

    @app.put("/api/settings/notifications")
    async def update_notification_settings(payload: NotificationSettingsUpdate) -> dict[str, Any]:
        if payload.only_notify_with_price is not None:
            processor.set_only_notify_with_price(payload.only_notify_with_price)
        if payload.click_target is not None:
            processor.set_notification_click_target(payload.click_target)
        return {
            "only_notify_with_price": processor.only_notify_with_price(),
            "click_target": processor.notification_click_target(),
        }

    @app.get("/api/ai/models")
    async def list_ai_models() -> dict[str, Any]:
        try:
            return {"models": await ai.list_models()}
        except AIConfigurationError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except Exception as exc:
            raise HTTPException(status_code=502, detail=f"model list failed: {exc}") from exc

    @app.post("/api/ai/test")
    async def test_ai() -> dict[str, Any]:
        parsed, error = await ai.extract("GTA 6\nPrice: $10\nDM @example_seller", image_path=None)
        return {"ok": error is None, "parsed": parsed, "error": error}

    @app.get("/api/watchlist")
    async def get_watchlist() -> list[dict[str, Any]]:
        return db.list_watchlist()

    @app.post("/api/watchlist", status_code=201)
    async def add_watchlist_item(payload: WatchlistItem) -> dict[str, Any]:
        return db.upsert_watchlist(payload.name, payload.enabled)

    @app.delete("/api/watchlist/{item_id}", status_code=204)
    async def delete_watchlist_item(item_id: int) -> None:
        if not db.delete_watchlist(item_id):
            raise HTTPException(status_code=404, detail="watchlist item not found")

    @app.post("/api/devices", status_code=201)
    async def register_device(payload: DeviceRegistration) -> dict[str, Any]:
        # This registers the future mobile push target. FCM/APNs/Web Push delivery is intentionally
        # kept behind the frontend/provider choice; the local WebSocket stream works immediately.
        return db.register_device(payload.token, payload.platform, payload.endpoint)

    @app.post("/api/ingest/web")
    async def ingest_web_message(payload: WebMessageIngest) -> dict[str, Any]:
        source = payload.source.strip().removeprefix("@").removeprefix("https://t.me/")
        channel = db.get_channel_by_source(source)
        if not channel or not channel.get("enabled"):
            raise HTTPException(status_code=404, detail="channel is not enabled")
        media = _save_web_media(
            settings.media_dir,
            source,
            payload.external_id,
            payload.media_data_urls,
        )
        raw = {
            "origin": "telegram_web",
            "external_id": payload.external_id,
            "telegram_url": payload.telegram_url,
        }
        if len(media) > 1:
            items = [
                {
                    "telegram_message_id": _web_message_id(
                        source, payload.external_id, index
                    ),
                    "text": payload.text if index == 0 else "",
                    "channel_title": payload.channel_title,
                    "posted_at": payload.posted_at,
                    "media_path": filename,
                    "media_mime": mime,
                    "raw": raw,
                }
                for index, (filename, mime) in enumerate(media)
            ]
            result = await processor.process_album(
                channel=channel,
                album_id=f"web:{source}:{payload.external_id}",
                items=items,
                primary_telegram_message_id=items[0]["telegram_message_id"],
            )
        else:
            media_path, media_mime = media[0] if media else (None, None)
            result = await processor.process(
                channel=channel,
                telegram_message_id=_web_message_id(source, payload.external_id),
                text=payload.text,
                channel_title=payload.channel_title,
                posted_at=payload.posted_at,
                media_path=media_path,
                media_mime=media_mime,
                raw=raw,
            )
        return {"accepted": True, "notification_created": result is not None}

    @app.post("/api/telegram/reload")
    async def reload_telegram() -> dict[str, Any]:
        if not telegram.configured:
            raise HTTPException(
                status_code=400, detail="Telegram API credentials are not configured"
            )
        return {"resolved_channels": await telegram.reload_channels()}

    @app.post("/api/telegram/restart")
    async def restart_telegram() -> dict[str, Any]:
        if not telegram.configured:
            raise HTTPException(
                status_code=400, detail="Telegram API credentials are not configured"
            )
        return {"task_started": await restart_telegram_listener()}

    @app.get("/api/telegram/dialogs")
    async def list_telegram_dialogs() -> list[dict[str, Any]]:
        if not telegram.configured:
            raise HTTPException(
                status_code=400, detail="Telegram API credentials are not configured"
            )
        try:
            return await telegram.list_dialog_channels()
        except RuntimeError as exc:
            raise HTTPException(status_code=503, detail=str(exc)) from exc

    @app.websocket("/ws/notifications")
    async def notification_socket(websocket: WebSocket) -> None:
        await hub.connect(websocket)
        try:
            await websocket.send_json({"type": "connected"})
            while True:
                # Keep the connection alive and allow a client to send a ping/ack payload.
                await websocket.receive_text()
        except WebSocketDisconnect:
            pass
        finally:
            await hub.disconnect(websocket)

    return app


app = create_app()
