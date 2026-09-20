from __future__ import annotations

from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

Category = Literal["game", "account", "other"]
Availability = Literal["available", "sold", "unknown"]


class ChannelCreate(BaseModel):
    source: str = Field(min_length=1, max_length=255)
    display_name: str | None = Field(default=None, max_length=255)
    enabled: bool = True

    @field_validator("source")
    @classmethod
    def clean_source(cls, value: str) -> str:
        value = value.strip()
        if value.startswith("https://t.me/"):
            value = value.removeprefix("https://t.me/").split("/", 1)[0]
        value = value.removeprefix("@")
        if not value:
            raise ValueError("source cannot be empty")
        return value


class ChannelUpdate(BaseModel):
    display_name: str | None = Field(default=None, max_length=255)
    enabled: bool | None = None


class WatchlistItem(BaseModel):
    name: str = Field(min_length=1, max_length=160)
    enabled: bool = True


class AISettingsUpdate(BaseModel):
    api_key: str | None = Field(default=None, max_length=4096)
    base_url: str | None = Field(default=None, max_length=500)
    model: str | None = Field(default=None, max_length=255)
    vision_model: str | None = Field(default=None, max_length=255)
    enable_vision: bool | None = None


class NotificationSettingsUpdate(BaseModel):
    only_notify_with_price: bool | None = None
    click_target: Literal["app", "telegram"] | None = None


class DeviceRegistration(BaseModel):
    token: str = Field(min_length=1, max_length=4096)
    platform: str = Field(min_length=1, max_length=40)
    endpoint: str | None = Field(default=None, max_length=1000)


class MessageOut(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: int
    source: str
    channel_title: str | None = None
    telegram_message_id: int
    text: str
    posted_at: datetime | None = None
    media_url: str | None = None
    media_mime: str | None = None
    parsed: dict[str, Any] = Field(default_factory=dict)
    urgent: bool = False
    created_at: datetime


class NotificationOut(BaseModel):
    id: int
    message_id: int
    title: str
    body: str
    urgent: bool
    read_at: datetime | None = None
    created_at: datetime
    message: dict[str, Any] | None = None
