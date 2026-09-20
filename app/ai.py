from __future__ import annotations

import base64
import json
import mimetypes
import re
from pathlib import Path
from typing import Any

import httpx

from .config import Settings
from .parser import normalize_ai_result

EXTRACTION_SYSTEM_PROMPT = """You extract structured marketplace-listing data from Telegram messages.
Messages may be English, Persian, Arabic, emoji-heavy, or a mixture. Do not invent missing data.
Return only a JSON object with these keys:
{
  "category": "game" | "account" | "other",
  "item_names": [string],
  "price": string | null,
  "currency": string | null,
  "availability": "available" | "sold" | "unknown",
  "contact_handles": [string],
  "seller_name": string | null,
  "summary": string,
  "confidence": number
}

Use the original spelling for item names and preserve @ handles. A price is only a price when
the message labels it as a price/cost or supplies a currency; numbers in names such as GTA 6
are not prices. A handle that is a bot or channel may still be included if it is the only contact.
"""

VISION_SYSTEM_PROMPT = """You are the image-reading stage of a Telegram game-listing watcher.
Inspect every supplied image and briefly list visible game names, account names, prices, currency,
availability words, and seller/contact handles. Do not invent anything. If text is unreadable,
say so. Return plain text that another text model can use as evidence.
"""


class AIConfigurationError(RuntimeError):
    pass


class GapGPTClient:
    """OpenAI-compatible client for GapGPT or another compatible gateway."""

    def __init__(self, settings: Settings, db: Any):
        self.settings = settings
        self.db = db
        self._runtime: dict[str, Any] = {
            "api_key": settings.gapgpt_api_key,
            "base_url": settings.gapgpt_base_url,
            "model": settings.gapgpt_model,
            "vision_model": settings.gapgpt_vision_model,
            "enable_vision": settings.ai_enable_vision,
        }

    def _get(self, key: str) -> Any:
        stored = self.db.get_setting(f"ai.{key}")
        if stored is not None:
            if key == "enable_vision":
                return stored == "1"
            return stored
        return self._runtime.get(key)

    def public_settings(self) -> dict[str, Any]:
        api_key = self._get("api_key")
        return {
            "configured": bool(api_key and self._get("base_url") and self._get("model")),
            "api_key_set": bool(api_key),
            "base_url": self._get("base_url"),
            "model": self._get("model"),
            "vision_model": self._get("vision_model") or self._get("model"),
            "enable_vision": bool(self._get("enable_vision")),
        }

    def update_settings(
        self,
        *,
        api_key: str | None = None,
        base_url: str | None = None,
        model: str | None = None,
        vision_model: str | None = None,
        enable_vision: bool | None = None,
    ) -> dict[str, Any]:
        # The key is deliberately never returned. It is stored in the local SQLite DB only
        # when entered through the settings API; use an environment variable for deployments.
        values = {
            "api_key": api_key.strip() if api_key is not None and api_key.strip() else None,
            "base_url": base_url.strip().rstrip("/") if base_url else None,
            "model": model.strip() if model else None,
            "vision_model": vision_model.strip() if vision_model else None,
        }
        for key, value in values.items():
            if value is not None:
                self.db.set_setting(f"ai.{key}", value)
        if enable_vision is not None:
            self.db.set_setting("ai.enable_vision", "1" if enable_vision else "0")
        return self.public_settings()

    def _connection(self) -> tuple[str, str]:
        api_key = self._get("api_key")
        base_url = self._get("base_url")
        missing = [
            name for name, value in (("api_key", api_key), ("base_url", base_url)) if not value
        ]
        if missing:
            raise AIConfigurationError(f"AI is not configured; missing {', '.join(missing)}")
        return str(api_key), str(base_url).rstrip("/")

    def _credentials(self, model_key: str = "model") -> tuple[str, str, str]:
        api_key, base_url = self._connection()
        model = self._get(model_key)
        if model_key == "vision_model" and not model:
            model = self._get("model")
        if not model:
            raise AIConfigurationError(f"AI is not configured; missing {model_key}")
        return api_key, base_url, str(model)

    @staticmethod
    def _content_text(content: Any) -> str:
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            return "".join(item.get("text", "") for item in content if isinstance(item, dict))
        return str(content or "")

    @staticmethod
    def _parse_json(text: str) -> dict[str, Any]:
        text = text.strip()
        try:
            value = json.loads(text)
            return value if isinstance(value, dict) else {}
        except json.JSONDecodeError:
            match = re.search(r"\{.*\}", text, flags=re.DOTALL)
            if not match:
                return {}
            try:
                value = json.loads(match.group(0))
                return value if isinstance(value, dict) else {}
            except json.JSONDecodeError:
                return {}

    def _image_content(self, image_path: Path) -> dict[str, Any] | None:
        if not image_path.exists() or image_path.stat().st_size > 8 * 1024 * 1024:
            return None
        mime = mimetypes.guess_type(image_path.name)[0] or "image/jpeg"
        encoded = base64.b64encode(image_path.read_bytes()).decode("ascii")
        return {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{encoded}"}}

    async def _post_chat(self, payload: dict[str, Any]) -> dict[str, Any]:
        api_key, base_url = self._connection()
        headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}
        timeout = httpx.Timeout(float(self.settings.ai_timeout_seconds), connect=15.0)
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post(
                f"{base_url}/chat/completions", headers=headers, json=payload
            )
            # Some OpenAI-compatible gateways reject response_format even though chat works.
            if response.status_code in {400, 404, 422} and "response_format" in payload:
                retry_payload = dict(payload)
                retry_payload.pop("response_format", None)
                response = await client.post(
                    f"{base_url}/chat/completions", headers=headers, json=retry_payload
                )
            response.raise_for_status()
            value = response.json()
            if not isinstance(value, dict):
                raise TypeError("AI provider returned a non-object response")
            return value

    async def analyze_images(self, image_paths: list[Path]) -> tuple[str | None, str | None]:
        """Describe an album's images with the selected vision model."""
        try:
            _, _, model = self._credentials("vision_model")
        except AIConfigurationError as exc:
            return None, str(exc)
        content: list[dict[str, Any]] = [
            {
                "type": "text",
                "text": "Read these listing images and report the visible evidence.",
            }
        ]
        for image_path in image_paths[:12]:
            image = self._image_content(image_path)
            if image:
                content.append(image)
        if len(content) == 1:
            return None, "No readable images were available for vision analysis"
        payload = {
            "model": model,
            "temperature": 0,
            "messages": [
                {"role": "system", "content": VISION_SYSTEM_PROMPT},
                {"role": "user", "content": content},
            ],
        }
        try:
            response = await self._post_chat(payload)
            choices = response.get("choices") or []
            result = choices[0].get("message", {}).get("content", "") if choices else ""
            result = self._content_text(result).strip()
            if not result:
                raise RuntimeError("vision model returned no image description")
            return result, None
        except (httpx.HTTPError, TypeError, ValueError, RuntimeError, OSError) as exc:
            return None, f"{type(exc).__name__}: {exc}"

    async def extract(
        self,
        text: str,
        *,
        image_path: Path | None = None,
        image_paths: list[Path] | None = None,
    ) -> tuple[dict[str, Any], str | None]:
        """Run vision first when enabled, then give the evidence to the text extractor."""
        paths = image_paths or ([image_path] if image_path else [])
        errors: list[str] = []
        image_context: str | None = None
        if paths and bool(self._get("enable_vision")):
            image_context, vision_error = await self.analyze_images(paths)
            if vision_error:
                errors.append(f"vision: {vision_error}")
        try:
            _, _, model = self._credentials()
        except AIConfigurationError as exc:
            errors.append(str(exc))
            return normalize_ai_result({}, text), "; ".join(errors)

        user_text = text or "[message has no text]"
        if image_context:
            user_text += f"\n\nImage analysis from the vision model:\n{image_context}"
        payload = {
            "model": model,
            "temperature": 0,
            "messages": [
                {"role": "system", "content": EXTRACTION_SYSTEM_PROMPT},
                {"role": "user", "content": user_text},
            ],
            "response_format": {"type": "json_object"},
        }
        try:
            response = await self._post_chat(payload)
            choices = response.get("choices") or []
            content = choices[0].get("message", {}).get("content", "") if choices else ""
            parsed = self._parse_json(self._content_text(content))
            if not parsed:
                raise RuntimeError("AI provider returned no JSON extraction")
            return normalize_ai_result(parsed, text), "; ".join(errors) or None
        except (httpx.HTTPError, TypeError, ValueError, RuntimeError, OSError) as exc:
            errors.append(f"text: {type(exc).__name__}: {exc}")
            return normalize_ai_result({}, text), "; ".join(errors)

    async def list_models(self) -> list[dict[str, Any]]:
        api_key, base_url = self._connection()
        headers = {"Authorization": f"Bearer {api_key}"}
        timeout = httpx.Timeout(float(self.settings.ai_timeout_seconds), connect=15.0)
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.get(f"{base_url}/models", headers=headers)
            response.raise_for_status()
            data = response.json()
        models = data.get("data", data) if isinstance(data, dict) else data
        if not isinstance(models, list):
            return []
        result = []
        for item in models:
            if isinstance(item, str):
                result.append({"id": item})
            elif isinstance(item, dict) and item.get("id"):
                result.append({"id": item["id"], "owned_by": item.get("owned_by")})
        return result
