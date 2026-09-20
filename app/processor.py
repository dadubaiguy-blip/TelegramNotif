from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import Any

from .ai import GapGPTClient
from .database import Database
from .notifications import NotificationHub
from .parser import apply_watchlist, is_game_sale_listing


def _as_iso(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, datetime):
        return value.isoformat()
    return str(value)


def _has_price(parsed: dict[str, Any]) -> bool:
    value = parsed.get("price")
    if value is None:
        return False
    return str(value).strip().casefold() not in {"", "none", "null", "unknown", "n/a", "not found"}


def _telegram_url(row: dict[str, Any]) -> str | None:
    source = str(row.get("source", "")).lstrip("@")
    message_id = row.get("telegram_message_id")
    if not source or not message_id:
        return None
    if source.lstrip("-").isdigit():
        try:
            internal_id = abs(int(source)) - 1_000_000_000_000
            if internal_id > 0:
                return f"https://t.me/c/{internal_id}/{int(message_id)}"
        except ValueError:
            return None
        return None
    return f"https://t.me/{source}/{int(message_id)}"


class MessageProcessor:
    def __init__(
        self,
        db: Database,
        ai: GapGPTClient,
        hub: NotificationHub,
        media_dir: Path,
        notify_only_with_price: bool = False,
    ):
        self.db = db
        self.ai = ai
        self.hub = hub
        self.media_dir = media_dir
        self.notify_only_with_price_default = notify_only_with_price

    def only_notify_with_price(self) -> bool:
        return True

    def set_only_notify_with_price(self, value: bool) -> bool:
        self.db.set_setting("notifications.only_with_price", "1")
        return True

    def notification_click_target(self) -> str:
        return self.db.get_setting("notifications.click_target") or "app"

    def set_notification_click_target(self, value: str) -> str:
        if value not in {"app", "telegram"}:
            raise ValueError("click target must be app or telegram")
        self.db.set_setting("notifications.click_target", value)
        return value

    async def process(
        self,
        *,
        channel: dict[str, Any],
        telegram_message_id: int,
        text: str,
        channel_title: str | None = None,
        sender_handle: str | None = None,
        posted_at: Any = None,
        media_path: str | None = None,
        media_mime: str | None = None,
        raw: dict[str, Any] | None = None,
    ) -> dict[str, Any] | None:
        row = self.db.insert_message(
            channel_id=int(channel["id"]),
            telegram_message_id=telegram_message_id,
            source=str(channel["source"]),
            channel_title=channel_title or channel.get("display_name"),
            sender_handle=sender_handle,
            text=text or "",
            posted_at=_as_iso(posted_at),
            media_path=media_path,
            media_mime=media_mime,
            raw=raw,
        )
        if not row:
            return None
        image_paths = [self.media_dir / media_path] if media_path else []
        return await self._finish(
            channel=channel,
            rows=[row],
            combined_text=text or "",
            image_paths=image_paths,
        )

    async def process_album(
        self,
        *,
        channel: dict[str, Any],
        album_id: str,
        items: list[dict[str, Any]],
    ) -> dict[str, Any] | None:
        rows: list[dict[str, Any]] = []
        for item in items:
            row = self.db.insert_message(
                channel_id=int(channel["id"]),
                telegram_message_id=int(item["telegram_message_id"]),
                album_id=album_id,
                source=str(channel["source"]),
                channel_title=item.get("channel_title") or channel.get("display_name"),
                sender_handle=item.get("sender_handle"),
                text=item.get("text", "") or "",
                posted_at=_as_iso(item.get("posted_at")),
                media_path=item.get("media_path"),
                media_mime=item.get("media_mime"),
                raw=item.get("raw"),
            )
            if row:
                rows.append(row)
        if not rows:
            rows = self.db.list_album_messages(album_id)
        if not rows:
            return None
        combined_text = "\n\n".join(row.get("text", "") for row in rows if row.get("text"))
        image_paths = [self.media_dir / row["media_path"] for row in rows if row.get("media_path")]
        return await self._finish(
            channel=channel,
            rows=rows,
            combined_text=combined_text,
            image_paths=image_paths,
        )

    async def _finish(
        self,
        *,
        channel: dict[str, Any],
        rows: list[dict[str, Any]],
        combined_text: str,
        image_paths: list[Path],
    ) -> dict[str, Any] | None:
        parsed, processing_error = await self.ai.extract(
            combined_text,
            image_paths=image_paths,
        )
        watchlist = [item["name"] for item in self.db.list_watchlist(enabled_only=True)]
        parsed = apply_watchlist(parsed, combined_text, watchlist)
        if len(rows) > 1:
            parsed["album_count"] = len(rows)
        urgent = bool(parsed.get("urgent"))
        updated_rows: list[dict[str, Any]] = []
        for row in rows:
            updated = (
                self.db.update_message_parsed(
                    int(row["id"]), parsed, urgent, processing_error=processing_error
                )
                or row
            )
            updated_rows.append(updated)

        # Keep all messages for review, but alert only for identified, priced game-sale posts.
        # This deliberately excludes accounts, giveaways, news, chatter, and unknown content.
        if not is_game_sale_listing(parsed, combined_text) or not _has_price(parsed):
            return None

        primary = updated_rows[0]
        item_names = parsed.get("item_names") or []
        item_label = ", ".join(str(item) for item in item_names[:3]) or "New listing"
        availability = parsed.get("availability", "unknown")
        handles = ", ".join(parsed.get("contact_handles") or []) or "No contact detected"
        album_label = f" · {len(updated_rows)} images" if len(updated_rows) > 1 else ""
        title = f"{'URGENT: ' if urgent else ''}{item_label}{album_label}"
        body = (
            f"Game listing · {availability}\n"
            f"DM: {handles}\n"
            f"Source: @{channel['source'].lstrip('@')}"
        )
        payload = self.api_message(primary)
        notification = self.db.create_notification(
            message_id=int(primary["id"]),
            title=title,
            body=body,
            payload=payload,
            urgent=urgent,
        )
        event = {
            "type": "new_notification",
            "notification": self.notification_payload(notification, payload),
        }
        await self.hub.broadcast(event)
        return event["notification"]

    def message_payload(self, row: dict[str, Any]) -> dict[str, Any]:
        parsed = Database.parse_json(row.get("parsed_json"))
        album_id = row.get("album_id")
        album_rows = self.db.list_album_messages(str(album_id)) if album_id else [row]
        media_items = [
            {
                "message_id": item["id"],
                "media_path": item.get("media_path"),
                "media_mime": item.get("media_mime"),
            }
            for item in album_rows
            if item.get("media_path")
        ]
        combined_text = "\n\n".join(item.get("text", "") for item in album_rows if item.get("text"))
        return {
            "id": row["id"],
            "source": row["source"],
            "channel_title": row.get("channel_title"),
            "telegram_message_id": row["telegram_message_id"],
            "text": combined_text or row.get("text", ""),
            "posted_at": row.get("posted_at"),
            "album_id": album_id,
            "album_count": len(album_rows) if album_id else 1,
            "media_items": media_items,
            "telegram_url": _telegram_url(row),
            "parsed": parsed,
            "urgent": bool(row.get("urgent")),
            "processing_error": row.get("processing_error"),
            "created_at": row.get("created_at"),
        }

    def notification_payload(
        self, notification: dict[str, Any], message: dict[str, Any]
    ) -> dict[str, Any]:
        return {
            "id": notification["id"],
            "message_id": notification["message_id"],
            "title": notification["title"],
            "body": notification["body"],
            "urgent": bool(notification["urgent"]),
            "read_at": notification.get("read_at"),
            "created_at": notification["created_at"],
            "message": message if "media_urls" in message else self._message_with_urls(message),
            "click_target": self.notification_click_target(),
            "telegram_url": message.get("telegram_url"),
        }

    @staticmethod
    def _message_with_urls(
        message: dict[str, Any], media_base_url: str = "/api/messages"
    ) -> dict[str, Any]:
        payload = dict(message)
        media_items = payload.pop("media_items", []) or []
        urls = [
            {
                "message_id": item["message_id"],
                "url": f"{media_base_url}/{item['message_id']}/media",
                "mime": item.get("media_mime"),
            }
            for item in media_items
        ]
        payload["media_urls"] = urls
        payload["media_url"] = urls[0]["url"] if urls else None
        return payload

    def api_message(
        self, row: dict[str, Any], media_base_url: str = "/api/messages"
    ) -> dict[str, Any]:
        return self._message_with_urls(self.message_payload(row), media_base_url)

    def api_notification(
        self, row: dict[str, Any], media_base_url: str = "/api/messages"
    ) -> dict[str, Any]:
        message = self.db.get_message(int(row["message_id"]))
        message_payload = self.api_message(message, media_base_url) if message else None
        return {
            "id": row["id"],
            "message_id": row["message_id"],
            "title": row["title"],
            "body": row["body"],
            "urgent": bool(row["urgent"]),
            "read_at": row.get("read_at"),
            "created_at": row["created_at"],
            "message": message_payload,
            "click_target": self.notification_click_target(),
            "telegram_url": message_payload.get("telegram_url") if message_payload else None,
        }
