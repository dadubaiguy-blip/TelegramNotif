from __future__ import annotations

import asyncio
import logging
import mimetypes
from pathlib import Path
from typing import Any

from .config import Settings
from .database import Database
from .processor import MessageProcessor

logger = logging.getLogger(__name__)


class TelegramService:
    """Long-running Telethon user-session listener.

    A user session is intentional here: bots cannot generally read arbitrary channels, especially
    private channels, unless they were explicitly added with the required permissions.
    """

    def __init__(self, settings: Settings, db: Database, processor: MessageProcessor):
        self.settings = settings
        self.db = db
        self.processor = processor
        self.client: Any = None
        self._stop_event = asyncio.Event()
        self._handler: Any = None
        self._album_buffers: dict[tuple[int, int], list[Any]] = {}
        self._album_tasks: dict[tuple[int, int], asyncio.Task[Any]] = {}

    @property
    def configured(self) -> bool:
        return bool(self.settings.telegram_api_id and self.settings.telegram_api_hash)

    async def run(self) -> None:
        if not self.configured:
            logger.warning("Telegram listener disabled: TELEGRAM_API_ID/HASH are not configured")
            return
        try:
            from telethon import TelegramClient, events
        except ImportError:
            logger.exception("Telethon is not installed; install project dependencies first")
            return

        self.client = TelegramClient(
            self.settings.telegram_session,
            self.settings.telegram_api_id,
            self.settings.telegram_api_hash,
        )
        try:
            await self.client.connect()
            if not await self.client.is_user_authorized():
                logger.error(
                    "Telegram session is not authorized. Run: python scripts/auth_telegram.py"
                )
                return
            chats = await self._resolve_enabled_channels()
            if not chats:
                logger.warning("No Telegram channels resolved; listener is idle")
            else:
                self._handler = events.NewMessage(chats=chats)
                self.client.add_event_handler(self._on_new_message, self._handler)
            logger.info("Telegram listener connected; watching %d channels", len(chats))
            await self.client.run_until_disconnected()
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("Telegram listener stopped unexpectedly")
        finally:
            if self.client:
                await self.client.disconnect()

    async def stop(self) -> None:
        for task in self._album_tasks.values():
            task.cancel()
        self._album_tasks.clear()
        self._album_buffers.clear()
        if self.client:
            await self.client.disconnect()

    async def reload_channels(self) -> int:
        """Refresh the chat filter after the API adds/enables a channel."""
        if not self.client or not self.client.is_connected():
            return 0
        from telethon import events

        if self._handler is not None:
            self.client.remove_event_handler(self._on_new_message, self._handler)
        chats = await self._resolve_enabled_channels()
        self._handler = None
        if chats:
            self._handler = events.NewMessage(chats=chats)
            self.client.add_event_handler(self._on_new_message, self._handler)
        return len(chats)

    async def list_dialog_channels(self) -> list[dict[str, Any]]:
        """Return channels visible to the authorized Telegram user for UI selection."""
        if not self.client or not self.client.is_connected():
            raise RuntimeError("Telegram listener is not connected")
        result: list[dict[str, Any]] = []
        async for dialog in self.client.iter_dialogs():
            entity = dialog.entity
            is_channel = bool(getattr(entity, "broadcast", False))
            is_supergroup = bool(getattr(entity, "megagroup", False))
            if not (is_channel or is_supergroup):
                continue
            dialog_id = int(dialog.id)
            username = getattr(entity, "username", None)
            selected = bool(
                (username and self.db.get_channel_by_source(username))
                or self.db.get_channel_by_telegram_id(abs(dialog_id))
                or self.db.get_channel_by_telegram_id(int(getattr(entity, "id", 0)))
            )
            result.append(
                {
                    "source": username or str(dialog_id),
                    "telegram_id": dialog_id,
                    "title": getattr(entity, "title", None) or dialog.name,
                    "username": f"@{username}" if username else None,
                    "private": not bool(username),
                    "selected": selected,
                }
            )
        return sorted(result, key=lambda item: (not item["private"], item["title"] or ""))

    async def _resolve_enabled_channels(self) -> list[int]:
        resolved: list[int] = []
        assert self.client is not None
        for channel in self.db.list_channels(enabled_only=True):
            source = str(channel["source"])
            try:
                lookup: str | int = int(source) if source.lstrip("-").isdigit() else source
                entity = await self.client.get_entity(lookup)
                telegram_id = int(entity.id)
                self.db.update_channel(int(channel["id"]), telegram_id=telegram_id, last_error=None)
                resolved.append(telegram_id)
            except Exception as exc:  # noqa: BLE001 - Telethon raises several entity-specific errors.
                logger.warning("Could not resolve Telegram source %s: %s", source, exc)
                self.db.update_channel(int(channel["id"]), last_error=str(exc)[:500])
        return resolved

    async def _download_media(self, message: Any, channel_id: int) -> tuple[str | None, str | None]:
        if not getattr(message, "photo", None) and not getattr(message, "document", None):
            return None, None
        document = getattr(message, "document", None)
        mime = getattr(document, "mime_type", None) if document else "image/jpeg"
        if mime and not mime.startswith("image/"):
            return None, None
        extension = mimetypes.guess_extension(mime or "image/jpeg") or ".jpg"
        filename = f"{channel_id}_{int(message.id)}{extension}"
        target = self.settings.media_dir / filename
        try:
            saved = await self.client.download_media(message, file=str(target))
            if not saved:
                return None, None
            return Path(saved).name, mime or "image/jpeg"
        except Exception as exc:  # noqa: BLE001 - media errors must not drop the message.
            logger.warning("Could not download media for message %s: %s", message.id, exc)
            return None, None

    async def _queue_album(self, event: Any, grouped_id: int) -> None:
        key = (int(event.chat_id), int(grouped_id))
        events = self._album_buffers.setdefault(key, [])
        message_id = int(event.message.id)
        if not any(int(item.message.id) == message_id for item in events):
            events.append(event)
        previous = self._album_tasks.get(key)
        if previous:
            previous.cancel()
        self._album_tasks[key] = asyncio.create_task(self._flush_album(key))

    async def _flush_album(self, key: tuple[int, int]) -> None:
        try:
            await asyncio.sleep(0.8)
        except asyncio.CancelledError:
            return
        events = sorted(self._album_buffers.pop(key, []), key=lambda item: int(item.message.id))
        self._album_tasks.pop(key, None)
        if events:
            await self._process_events(events)

    async def _process_events(self, events: list[Any]) -> None:
        first_event = events[0]
        chat = await first_event.get_chat()
        telegram_chat_id = int(first_event.chat_id)
        channel = self.db.get_channel_by_telegram_id(
            getattr(chat, "id", telegram_chat_id)
        ) or self.db.get_channel_by_telegram_id(abs(telegram_chat_id))
        if not channel:
            logger.warning("Ignoring message from unconfigured chat %s", telegram_chat_id)
            return
        title = getattr(chat, "title", None) or getattr(chat, "username", None)
        sender = await first_event.get_sender()
        username = getattr(sender, "username", None) if sender else None
        sender_handle = f"@{username}" if username else None
        items: list[dict[str, Any]] = []
        for event in events:
            message = event.message
            media_path, media_mime = await self._download_media(message, int(channel["id"]))
            items.append(
                {
                    "telegram_message_id": int(message.id),
                    "text": getattr(message, "message", "") or "",
                    "channel_title": title,
                    "sender_handle": sender_handle,
                    "posted_at": getattr(message, "date", None),
                    "media_path": media_path,
                    "media_mime": media_mime,
                    "raw": {
                        "telegram_chat_id": telegram_chat_id,
                        "message_id": int(message.id),
                        "sender_id": getattr(sender, "id", None) if sender else None,
                        "grouped_id": getattr(message, "grouped_id", None),
                        "has_media": bool(media_path),
                    },
                }
            )
        grouped_id = getattr(first_event.message, "grouped_id", None)
        if grouped_id is not None:
            await self.processor.process_album(
                channel=channel,
                album_id=f"{telegram_chat_id}:{int(grouped_id)}",
                items=items,
            )
        else:
            item = items[0]
            await self.processor.process(channel=channel, **item)

    async def _on_new_message(self, event: Any) -> None:
        try:
            grouped_id = getattr(event.message, "grouped_id", None)
            if grouped_id is not None:
                await self._queue_album(event, int(grouped_id))
            else:
                await self._process_events([event])
        except Exception:
            logger.exception("Failed to process Telegram message")
