from app.parser import apply_watchlist, heuristic_extract, normalize_ai_result

SAMPLE = """GTA 6 !!!!!!
GTA 5
FC 24
MORE GAME
@messiiiiii100000 خرید or ❌ فروخته شد
📦 Gta vi

➖➖➖➖➖➖➖➖➖
🔻 @XCrvcK
🤖 @xCravck_bot foroojhte shod"""


def test_heuristic_extract_does_not_call_game_numbers_prices():
    parsed = heuristic_extract(SAMPLE)
    assert parsed["availability"] == "sold"
    assert "GTA 6" in parsed["item_names"]
    assert parsed["price"] is None
    assert "@messiiiiii100000" in parsed["contact_handles"]


def test_watchlist_marks_urgent():
    parsed = apply_watchlist(heuristic_extract("GTA 6\nPrice: $30"), "GTA 6\nPrice: $30", ["GTA 6"])
    assert parsed["urgent"] is True
    assert parsed["matched_watchlist"] == ["GTA 6"]


def test_normalize_ai_result_accepts_aliases():
    parsed = normalize_ai_result(
        {"games": ["GTA VI"], "contacts": "@seller", "availability": "available"},
        "GTA VI",
    )
    assert parsed["item_names"] == ["GTA VI"]
    assert parsed["contact_handles"] == ["@seller"]
