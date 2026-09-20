from __future__ import annotations

import json
import sqlite3
import threading
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from .config import DEFAULT_CHANNELS


def utc_now() -> str:
    return datetime.now(UTC).isoformat()


class Database:
    """Small SQLite repository used by both the API and Telegram worker."""

    def __init__(self, path: Path):
        self.path = path
        self._lock = threading.RLock()
        self._conn: sqlite3.Connection | None = None

    def connect(self) -> None:
        if self._conn is not None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA foreign_keys=ON")

    @property
    def conn(self) -> sqlite3.Connection:
        if self._conn is None:
            self.connect()
        assert self._conn is not None
        return self._conn

    def close(self) -> None:
        with self._lock:
            if self._conn is not None:
                self._conn.close()
                self._conn = None

    def initialize(self) -> None:
        with self._lock:
            self.conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS channels (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source TEXT NOT NULL UNIQUE,
                    display_name TEXT,
                    telegram_id INTEGER,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    last_error TEXT,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS watchlist (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    channel_id INTEGER NOT NULL REFERENCES channels(id) ON DELETE CASCADE,
                    telegram_message_id INTEGER NOT NULL,
                    source TEXT NOT NULL,
                    channel_title TEXT,
                    sender_handle TEXT,
                        text TEXT NOT NULL DEFAULT '',
                        posted_at TEXT,
                        media_path TEXT,
                        media_mime TEXT,
                        album_id TEXT,
                        raw_json TEXT,
                    parsed_json TEXT NOT NULL DEFAULT '{}',
                    urgent INTEGER NOT NULL DEFAULT 0,
                    processing_error TEXT,
                    created_at TEXT NOT NULL,
                    UNIQUE(channel_id, telegram_message_id)
                );

                CREATE TABLE IF NOT EXISTS notifications (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    message_id INTEGER NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    urgent INTEGER NOT NULL DEFAULT 0,
                    read_at TEXT,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS devices (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    token TEXT NOT NULL UNIQUE,
                    platform TEXT NOT NULL,
                    endpoint TEXT,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_at TEXT NOT NULL,
                    last_seen_at TEXT NOT NULL
                );

                CREATE INDEX IF NOT EXISTS idx_messages_created_at
                    ON messages(created_at DESC);
                CREATE INDEX IF NOT EXISTS idx_notifications_created_at
                    ON notifications(created_at DESC);
                """
            )
            self._ensure_column("messages", "album_id", "TEXT")
            for source in DEFAULT_CHANNELS:
                self.conn.execute(
                    """
                    INSERT INTO channels(source, display_name, enabled, created_at)
                    VALUES (?, ?, 1, ?)
                    ON CONFLICT(source) DO NOTHING
                    """,
                    (source, f"@{source}", utc_now()),
                )
            self.conn.commit()

    def _ensure_column(self, table: str, column: str, definition: str) -> None:
        columns = {
            row["name"] for row in self.conn.execute(f"PRAGMA table_info({table})").fetchall()
        }
        if column not in columns:
            self.conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")

    def _commit(self) -> None:
        self.conn.commit()

    @staticmethod
    def _row_dict(row: sqlite3.Row | None) -> dict[str, Any] | None:
        return dict(row) if row else None

    def get_setting(self, key: str) -> str | None:
        with self._lock:
            row = self.conn.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
            return row["value"] if row else None

    def set_setting(self, key: str, value: str | None) -> None:
        with self._lock:
            if value is None:
                self.conn.execute("DELETE FROM settings WHERE key = ?", (key,))
            else:
                self.conn.execute(
                    "INSERT INTO settings(key, value) VALUES (?, ?) "
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                    (key, value),
                )
            self._commit()

    def list_channels(self, enabled_only: bool = False) -> list[dict[str, Any]]:
        query = "SELECT * FROM channels"
        if enabled_only:
            query += " WHERE enabled = 1"
        query += " ORDER BY id"
        with self._lock:
            return [dict(row) for row in self.conn.execute(query).fetchall()]

    def get_channel(self, channel_id: int) -> dict[str, Any] | None:
        with self._lock:
            return self._row_dict(
                self.conn.execute("SELECT * FROM channels WHERE id = ?", (channel_id,)).fetchone()
            )

    def get_channel_by_source(self, source: str) -> dict[str, Any] | None:
        with self._lock:
            return self._row_dict(
                self.conn.execute("SELECT * FROM channels WHERE source = ?", (source,)).fetchone()
            )

    def get_channel_by_telegram_id(self, telegram_id: int) -> dict[str, Any] | None:
        with self._lock:
            return self._row_dict(
                self.conn.execute(
                    "SELECT * FROM channels WHERE telegram_id = ?", (telegram_id,)
                ).fetchone()
            )

    def upsert_channel(
        self,
        source: str,
        display_name: str | None = None,
        enabled: bool = True,
    ) -> dict[str, Any]:
        with self._lock:
            self.conn.execute(
                """
                INSERT INTO channels(source, display_name, enabled, created_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(source) DO UPDATE SET
                    display_name = COALESCE(excluded.display_name, channels.display_name),
                    enabled = excluded.enabled
                """,
                (source, display_name, int(enabled), utc_now()),
            )
            self._commit()
            return self.get_channel_by_source(source)  # type: ignore[return-value]

    def update_channel(self, channel_id: int, **values: Any) -> dict[str, Any] | None:
        allowed = {"display_name", "enabled", "last_error", "telegram_id"}
        updates = {key: value for key, value in values.items() if key in allowed}
        if not updates:
            return self.get_channel(channel_id)
        assignments = ", ".join(f"{key} = ?" for key in updates)
        params = [int(value) if isinstance(value, bool) else value for value in updates.values()]
        params.append(channel_id)
        with self._lock:
            self.conn.execute(f"UPDATE channels SET {assignments} WHERE id = ?", params)
            self._commit()
            return self.get_channel(channel_id)

    def delete_channel(self, channel_id: int) -> bool:
        with self._lock:
            cursor = self.conn.execute("DELETE FROM channels WHERE id = ?", (channel_id,))
            self._commit()
            return cursor.rowcount > 0

    def list_watchlist(self, enabled_only: bool = False) -> list[dict[str, Any]]:
        query = "SELECT * FROM watchlist"
        if enabled_only:
            query += " WHERE enabled = 1"
        query += " ORDER BY name COLLATE NOCASE"
        with self._lock:
            return [dict(row) for row in self.conn.execute(query).fetchall()]

    def upsert_watchlist(self, name: str, enabled: bool = True) -> dict[str, Any]:
        name = name.strip()
        with self._lock:
            self.conn.execute(
                """
                INSERT INTO watchlist(name, enabled, created_at) VALUES (?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET enabled = excluded.enabled
                """,
                (name, int(enabled), utc_now()),
            )
            self._commit()
            row = self.conn.execute("SELECT * FROM watchlist WHERE name = ?", (name,)).fetchone()
            return dict(row)

    def delete_watchlist(self, item_id: int) -> bool:
        with self._lock:
            cursor = self.conn.execute("DELETE FROM watchlist WHERE id = ?", (item_id,))
            self._commit()
            return cursor.rowcount > 0

    def insert_message(
        self,
        *,
        channel_id: int,
        telegram_message_id: int,
        album_id: str | None = None,
        source: str,
        channel_title: str | None,
        sender_handle: str | None,
        text: str,
        posted_at: str | None,
        media_path: str | None,
        media_mime: str | None,
        raw: dict[str, Any] | None,
    ) -> dict[str, Any] | None:
        with self._lock:
            try:
                cursor = self.conn.execute(
                    """
                    INSERT INTO messages(
                        channel_id, telegram_message_id, source, channel_title,
                        sender_handle, text, posted_at, media_path, media_mime,
                        album_id, raw_json, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        channel_id,
                        telegram_message_id,
                        source,
                        channel_title,
                        sender_handle,
                        text,
                        posted_at,
                        media_path,
                        media_mime,
                        album_id,
                        json.dumps(raw or {}, ensure_ascii=False),
                        utc_now(),
                    ),
                )
            except sqlite3.IntegrityError:
                return None
            self._commit()
            return self.get_message(cursor.lastrowid)

    def list_album_messages(self, album_id: str) -> list[dict[str, Any]]:
        with self._lock:
            return [
                dict(row)
                for row in self.conn.execute(
                    "SELECT * FROM messages WHERE album_id = ? ORDER BY telegram_message_id",
                    (album_id,),
                ).fetchall()
            ]

    def update_message_parsed(
        self,
        message_id: int,
        parsed: dict[str, Any],
        urgent: bool,
        processing_error: str | None = None,
    ) -> dict[str, Any] | None:
        with self._lock:
            self.conn.execute(
                """
                UPDATE messages
                SET parsed_json = ?, urgent = ?, processing_error = ?
                WHERE id = ?
                """,
                (json.dumps(parsed, ensure_ascii=False), int(urgent), processing_error, message_id),
            )
            self._commit()
            return self.get_message(message_id)

    def get_message(self, message_id: int) -> dict[str, Any] | None:
        with self._lock:
            return self._row_dict(
                self.conn.execute("SELECT * FROM messages WHERE id = ?", (message_id,)).fetchone()
            )

    def list_messages(
        self,
        *,
        limit: int = 100,
        offset: int = 0,
        source: str | None = None,
        category: str | None = None,
        urgent: bool | None = None,
    ) -> list[dict[str, Any]]:
        clauses: list[str] = []
        params: list[Any] = []
        if source:
            clauses.append("m.source = ?")
            params.append(source)
        if urgent is not None:
            clauses.append("m.urgent = ?")
            params.append(int(urgent))
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._lock:
            rows = self.conn.execute(
                f"""
                SELECT m.* FROM messages m
                {where}
                ORDER BY m.created_at DESC
                LIMIT ? OFFSET ?
                """,
                (*params, min(max(limit, 1), 500), max(offset, 0)),
            ).fetchall()
            result = [dict(row) for row in rows]
        if category:
            result = [
                row
                for row in result
                if self.parse_json(row.get("parsed_json")).get("category") == category
            ]
        return result

    def create_notification(
        self,
        *,
        message_id: int,
        title: str,
        body: str,
        payload: dict[str, Any],
        urgent: bool,
    ) -> dict[str, Any]:
        with self._lock:
            cursor = self.conn.execute(
                """
                INSERT INTO notifications(message_id, title, body, payload_json, urgent, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    message_id,
                    title,
                    body,
                    json.dumps(payload, ensure_ascii=False),
                    int(urgent),
                    utc_now(),
                ),
            )
            self._commit()
            row = self.conn.execute(
                "SELECT * FROM notifications WHERE id = ?", (cursor.lastrowid,)
            ).fetchone()
            return dict(row)

    def list_notifications(
        self, limit: int = 100, unread_only: bool = False
    ) -> list[dict[str, Any]]:
        where = "WHERE read_at IS NULL" if unread_only else ""
        with self._lock:
            rows = self.conn.execute(
                f"SELECT * FROM notifications {where} ORDER BY created_at DESC LIMIT ?",
                (min(max(limit, 1), 500),),
            ).fetchall()
            return [dict(row) for row in rows]

    def mark_notification_read(self, notification_id: int) -> bool:
        with self._lock:
            cursor = self.conn.execute(
                "UPDATE notifications SET read_at = ? WHERE id = ?",
                (utc_now(), notification_id),
            )
            self._commit()
            return cursor.rowcount > 0

    def register_device(self, token: str, platform: str, endpoint: str | None) -> dict[str, Any]:
        with self._lock:
            self.conn.execute(
                """
                INSERT INTO devices(token, platform, endpoint, created_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(token) DO UPDATE SET
                    platform = excluded.platform,
                    endpoint = excluded.endpoint,
                    enabled = 1,
                    last_seen_at = excluded.last_seen_at
                """,
                (token, platform, endpoint, utc_now(), utc_now()),
            )
            self._commit()
            row = self.conn.execute("SELECT * FROM devices WHERE token = ?", (token,)).fetchone()
            return dict(row)

    def get_devices(self) -> list[dict[str, Any]]:
        with self._lock:
            return [
                dict(row)
                for row in self.conn.execute("SELECT * FROM devices WHERE enabled = 1").fetchall()
            ]

    @staticmethod
    def parse_json(value: str | None) -> dict[str, Any]:
        try:
            parsed = json.loads(value or "{}")
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            return {}
