package com.dadubaiguy.telegramnotif

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class ListingNotification(
    val id: Int,
    val messageId: Int,
    val title: String,
    val body: String,
    val urgent: Boolean,
    val createdAt: String,
    val clickTarget: String,
    val telegramUrl: String?,
    val source: String,
    val text: String,
    val mediaUrls: List<String>,
    val parsed: JSONObject,
)

data class ChannelInfo(
    val source: String,
    val displayName: String,
)

class ApiClient(private val settings: SettingsStore) {
    private val baseUrl: String
        get() = settings.serverUrl.trim().trimEnd('/')

    fun health(): JSONObject = JSONObject(request("GET", "/api/health"))

    fun getNotifications(unreadOnly: Boolean): List<ListingNotification> {
        val payload = JSONArray(
            request("GET", "/api/notifications?limit=100&unread_only=$unreadOnly"),
        )
        return buildList {
            for (index in 0 until payload.length()) {
                add(parseNotification(payload.getJSONObject(index)))
            }
        }
    }

    fun markNotificationRead(notificationId: Int) {
        request("POST", "/api/notifications/$notificationId/read")
    }

    fun updateAiSettings(
        apiKey: String,
        textModel: String,
        visionModel: String,
        enableVision: Boolean,
    ) {
        val payload = JSONObject()
        if (apiKey.isNotBlank()) payload.put("api_key", apiKey)
        if (textModel.isNotBlank()) payload.put("model", textModel)
        if (visionModel.isNotBlank()) payload.put("vision_model", visionModel)
        payload.put("enable_vision", enableVision)
        request("PUT", "/api/settings/ai", payload.toString())
    }

    fun updateNotificationSettings(onlyPriced: Boolean, clickTarget: String) {
        val payload = JSONObject()
            .put("only_notify_with_price", onlyPriced)
            .put("click_target", clickTarget)
        request("PUT", "/api/settings/notifications", payload.toString())
    }

    fun getSettings(): JSONObject = JSONObject(request("GET", "/api/settings"))

    fun getModels(): List<String> {
        val payload = JSONObject(request("GET", "/api/ai/models"))
        val models = payload.optJSONArray("models") ?: return emptyList()
        return buildList {
            for (index in 0 until models.length()) add(models.optString(index))
        }.filter { it.isNotBlank() }
    }

    fun getChannels(): List<ChannelInfo> = parseChannels(JSONArray(request("GET", "/api/channels")))

    fun getJoinedChannels(): List<ChannelInfo> =
        parseChannels(JSONArray(request("GET", "/api/telegram/dialogs")))

    fun addChannel(source: String, displayName: String? = null) {
        val payload = JSONObject().put("source", source.trim())
        if (!displayName.isNullOrBlank()) payload.put("display_name", displayName.trim())
        request("POST", "/api/channels", payload.toString())
    }

    fun fetchBitmap(path: String): Bitmap? {
        val connection = (absoluteUrl(path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseNotification(payload: JSONObject): ListingNotification {
        val message = payload.optJSONObject("message") ?: JSONObject()
        val mediaUrls = mutableListOf<String>()
        val media = message.optJSONArray("media_urls")
        if (media != null) {
            for (index in 0 until media.length()) {
                val item = media.optJSONObject(index) ?: continue
                item.optString("url").takeIf { it.isNotBlank() }?.let(mediaUrls::add)
            }
        }
        if (mediaUrls.isEmpty()) {
            message.optString("media_url").takeIf { it.isNotBlank() }?.let(mediaUrls::add)
        }
        return ListingNotification(
            id = payload.optInt("id"),
            messageId = payload.optInt("message_id"),
            title = payload.optString("title", "New Telegram listing"),
            body = payload.optString("body", ""),
            urgent = payload.optBoolean("urgent", false),
            createdAt = payload.optString("created_at", ""),
            clickTarget = payload.optString("click_target", "app"),
            telegramUrl = nullableString(payload, "telegram_url")
                ?: nullableString(message, "telegram_url"),
            source = message.optString("source", "unknown"),
            text = message.optString("text", ""),
            mediaUrls = mediaUrls,
            parsed = message.optJSONObject("parsed") ?: JSONObject(),
        )
    }

    private fun parseChannels(payload: JSONArray): List<ChannelInfo> = buildList {
        for (index in 0 until payload.length()) {
            val channel = payload.optJSONObject(index) ?: continue
            val source = channel.optString("source", channel.optString("id", ""))
            val name = channel.optString(
                "display_name",
                channel.optString("title", source),
            )
            if (source.isNotBlank()) add(ChannelInfo(source, name))
        }
    }

    private fun nullableString(payload: JSONObject, key: String): String? {
        if (!payload.has(key) || payload.isNull(key)) return null
        return payload.optString(key).takeIf { it.isNotBlank() && it != "null" }
    }

    private fun absoluteUrl(path: String): URL {
        if (path.startsWith("http://") || path.startsWith("https://")) return URL(path)
        return URL("$baseUrl/${path.trimStart('/')}")
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val connection = (absoluteUrl(path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            requestMethod = method
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(StandardCharsets.UTF_8))
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Backend returned HTTP $code: ${response.take(300)}")
            }
            response
        } finally {
            connection.disconnect()
        }
    }
}
