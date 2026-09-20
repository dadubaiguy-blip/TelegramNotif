package com.dadubaiguy.telegramnotif

import org.json.JSONObject

object ListingRules {
    private val giveawayMarkers = listOf(
        "giveaway",
        "give away",
        "free giveaway",
        "win a copy",
        "contest",
        "raffle",
        "قرعه کشی",
        "قرعه‌کشی",
        "قرعهکشی",
        "جایزه",
        "رایگان",
        "هدیه",
    )

    fun isEligibleGame(item: ListingNotification): Boolean {
        val parsed = item.parsed
        if (parsed.optString("category") != "game") return false
        if ((parsed.optJSONArray("item_names")?.length() ?: 0) == 0) return false
        if (parsed.optBoolean("is_giveaway", false)) return false
        if (giveawayMarkers.any { item.text.contains(it, ignoreCase = true) }) return false
        val price = parsed.opt("price")
        return price != null && price != JSONObject.NULL &&
            price.toString().trim().lowercase() !in missingPrices
    }

    fun dedupKey(item: ListingNotification): String {
        item.parsed.optString("listing_fingerprint").takeIf { it.isNotBlank() }?.let { return it }
        val names = item.parsed.optJSONArray("item_names")
        val normalizedNames = buildList {
            if (names != null) {
                for (index in 0 until names.length()) add(names.optString(index).trim().lowercase())
            }
        }.sorted().joinToString("|")
        return "$normalizedNames|${item.parsed.optString("price").trim().lowercase()}"
    }

    private val missingPrices = setOf("", "none", "null", "unknown", "n/a", "not found")
}
