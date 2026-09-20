from __future__ import annotations

import re
from typing import Any

HANDLE_RE = re.compile(r"(?<!\w)@[A-Za-z0-9_]{3,64}")
PRICE_RE = re.compile(
    r"(?P<label>price|قیمت|cost|fee)?\s*[:=]?\s*"
    r"(?P<amount>[\d][\d,\.\s]*)\s*"
    r"(?P<currency>تومان|تومن|ریال|دلار|ریال|irr|irt|tomans?|usd|eur|\$|€|£)?",
    re.IGNORECASE,
)

SOLD_MARKERS = (
    "فروخته شد",
    "فروخته‌شد",
    "فروختهشد",
    "فروخت",
    "sold",
    "foroojhte shod",
    "foroshte shod",
)
BUY_MARKERS = ("خرید", "خریدار", "buy", "wanted")
ACCOUNT_MARKERS = ("account", "اکانت", "حساب", "اکانـت")
GAME_MARKERS = ("game", "بازی", "gta", "fc ", "fifa", "xbox", "ps4", "ps5", "steam")


def _clean_line(line: str) -> str:
    line = re.sub(r"[\u200b\u200c\u200d]", "", line)
    line = re.sub(r"^[\s\-_=➖🔻📦✅❌⭐️🔥]+", "", line)
    return re.sub(r"\s+", " ", line).strip(" -_=|:!?！")


def _unique(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        key = value.casefold()
        if key not in seen:
            seen.add(key)
            result.append(value)
    return result


def _extract_price(text: str) -> tuple[str | None, str | None]:
    for match in PRICE_RE.finditer(text):
        amount = re.sub(r"\s+", "", match.group("amount"))
        currency = (match.group("currency") or "").strip()
        label = (match.group("label") or "").strip()
        # Do not mistake the number in a title such as "GTA 6" for a price.
        if currency or label:
            return amount, currency or None
    return None, None


def _candidate_items(lines: list[str], text: str) -> list[str]:
    candidates: list[str] = []
    for raw in lines:
        line = _clean_line(raw)
        if not line or len(line) < 2 or len(line) > 100:
            continue
        lower = line.casefold()
        if HANDLE_RE.fullmatch(line):
            continue
        if any(marker in lower for marker in SOLD_MARKERS + BUY_MARKERS):
            continue
        if re.search(r"\b(price|cost|fee)\b|قیمت", lower):
            continue
        if any(token in lower for token in ("http://", "https://", "t.me/", "telegram.me/")):
            continue
        if lower in {"more game", "more games", "game", "games", "بازی", "اکانت"}:
            continue
        if re.fullmatch(r"[\W_]+", line, flags=re.UNICODE):
            continue
        if re.search(r"[A-Za-z]", line) and (
            re.search(r"\d", line) or any(marker in lower for marker in GAME_MARKERS)
        ):
            candidates.append(line)
    return _unique(candidates)


def heuristic_extract(text: str) -> dict[str, Any]:
    """Best-effort parser used when AI is not configured or a provider fails."""
    normalized = text or ""
    lower = normalized.casefold()
    lines = normalized.splitlines()
    handles = _unique(HANDLE_RE.findall(normalized))
    price, currency = _extract_price(normalized)
    items = _candidate_items(lines, normalized)
    category = "account" if any(marker in lower for marker in ACCOUNT_MARKERS) else "game"
    if not items and category != "account":
        category = "other"
    if any(marker in lower for marker in SOLD_MARKERS) or "❌" in normalized:
        availability = "sold"
    elif any(marker in lower for marker in BUY_MARKERS) or "✅" in normalized:
        availability = "available"
    else:
        availability = "unknown"
    return {
        "category": category,
        "item_names": items,
        "price": price,
        "currency": currency,
        "availability": availability,
        "contact_handles": handles,
        "seller_name": None,
        "summary": " ".join(items[:3]) if items else normalized[:180].strip(),
        "confidence": 0.35,
    }


def normalize_ai_result(value: Any, original_text: str) -> dict[str, Any]:
    """Normalize slightly different model schemas into the app's stable contract."""
    if not isinstance(value, dict):
        return heuristic_extract(original_text)
    fallback = heuristic_extract(original_text)
    item_names = value.get("item_names", value.get("games", value.get("items", [])))
    if isinstance(item_names, str):
        item_names = [item_names]
    if not isinstance(item_names, list):
        item_names = fallback["item_names"]
    handles = value.get("contact_handles", value.get("contacts", value.get("seller_handles", [])))
    if isinstance(handles, str):
        handles = HANDLE_RE.findall(handles)
    if not isinstance(handles, list):
        handles = fallback["contact_handles"]
    category = value.get("category")
    if category not in {"game", "account", "other"}:
        category = fallback["category"]
    availability = value.get("availability", "unknown")
    if availability not in {"available", "sold", "unknown"}:
        availability = fallback["availability"]
    result = {
        "category": category,
        "item_names": _unique([str(item).strip() for item in item_names if str(item).strip()]),
        "price": str(value.get("price")) if value.get("price") is not None else fallback["price"],
        "currency": str(value.get("currency")) if value.get("currency") else fallback["currency"],
        "availability": availability,
        "contact_handles": _unique([str(item).strip() for item in handles if str(item).strip()]),
        "seller_name": value.get("seller_name") or value.get("seller") or None,
        "summary": str(value.get("summary") or fallback["summary"])[:500],
        "confidence": value.get("confidence", 0.7),
    }
    if not result["item_names"]:
        result["item_names"] = fallback["item_names"]
    if not result["contact_handles"]:
        result["contact_handles"] = fallback["contact_handles"]
    return result


def apply_watchlist(parsed: dict[str, Any], text: str, watchlist: list[str]) -> dict[str, Any]:
    haystack = " ".join([text, *(str(item) for item in parsed.get("item_names", []))]).casefold()
    matches = [name for name in watchlist if name.casefold() in haystack]
    parsed = dict(parsed)
    parsed["matched_watchlist"] = _unique(matches)
    parsed["urgent"] = bool(matches)
    return parsed
