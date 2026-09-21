package com.dadubaiguy.telegramnotif

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Best-effort Telegram Web watcher. Telegram Web is not a stable machine API, so this remains a
 * fallback for people who cannot use MTProto credentials. The saved WebView profile is shared with
 * TelegramWebLoginActivity and is deliberately not cleared when monitoring stops.
 */
class TelegramWebMonitor(
    context: Context,
    private val settings: SettingsStore,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var webView: WebView? = null
    private var channels: List<ChannelInfo> = emptyList()
    private var channelIndex = 0
    private var currentSource = ""
    private var running = false
    private var pageGeneration = 0

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        if (running) return
        running = true
        handler.post {
            val view = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.loadsImagesAutomatically = true
                settings.blockNetworkImage = false
                settings.userAgentString = settings.userAgentString + " TelegramNotif/0.6"
                addJavascriptInterface(WebBridge(), BRIDGE_NAME)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val generation = pageGeneration
                        handler.postDelayed({
                            if (running && generation == pageGeneration) extractVisibleMessages()
                        }, PAGE_SETTLE_MILLIS)
                    }
                }
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, true)
            }
            webView = view
            refreshChannels()
        }
    }

    fun destroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        val view = webView
        webView = null
        if (view != null) {
            view.stopLoading()
            view.removeJavascriptInterface(BRIDGE_NAME)
            view.removeAllViews()
            view.destroy()
        }
    }

    private fun refreshChannels() {
        if (!running) return
        executor.execute {
            val loaded = runCatching { ApiClient(settings).getChannels() }.getOrDefault(emptyList())
            handler.post {
                if (!running) return@post
                channels = loaded
                channelIndex = 0
                if (channels.isEmpty()) {
                    handler.postDelayed(::refreshChannels, EMPTY_RETRY_MILLIS)
                } else {
                    openNextChannel()
                }
            }
        }
    }

    private fun openNextChannel() {
        if (!running) return
        if (channelIndex >= channels.size) {
            handler.postDelayed(::refreshChannels, CYCLE_PAUSE_MILLIS)
            return
        }
        currentSource = channels[channelIndex++].source.trim().removePrefix("@").substringBefore('/')
        pageGeneration += 1
        val route = if (currentSource.startsWith("-")) currentSource else "@$currentSource"
        webView?.loadUrl("https://web.telegram.org/k/#$route")
        val generation = pageGeneration
        handler.postDelayed({
            if (running && generation == pageGeneration) openNextChannel()
        }, CHANNEL_WINDOW_MILLIS)
    }

    private fun extractVisibleMessages() {
        val sourceJson = JSONObject.quote(currentSource)
        val script = """
            (function() {
              const source = $sourceJson;
              const hash = function(value) {
                let h = 2166136261;
                for (let i = 0; i < value.length; i++) {
                  h ^= value.charCodeAt(i);
                  h = Math.imul(h, 16777619);
                }
                return (h >>> 0).toString(16);
              };
              let nodes = Array.from(document.querySelectorAll('[data-mid]'));
              if (!nodes.length) nodes = Array.from(document.querySelectorAll('.bubble'));
              const unique = [];
              const used = new Set();
              for (const node of nodes.slice(-16)) {
                const text = (node.innerText || '').trim().slice(0, 50000);
                const images = Array.from(node.querySelectorAll('img')).filter(function(img) {
                  return img.naturalWidth >= 80 && img.naturalHeight >= 80 && img.src;
                }).slice(0, 4);
                if (!text && !images.length) continue;
                const id = String(node.dataset.mid || node.dataset.messageId || node.id || hash(text));
                if (used.has(id)) continue;
                used.add(id);
                const link = Array.from(node.querySelectorAll('a[href]')).map(a => a.href)
                  .find(href => href && (href.startsWith('https://t.me/') || href.startsWith('tg://'))) || null;
                const time = node.querySelector('time[datetime]');
                unique.push({id: id, text: text, link: link, posted_at: time ? time.getAttribute('datetime') : null, images: images});
              }
              const imageData = async function(img) {
                try {
                  const scale = Math.min(1, 900 / Math.max(img.naturalWidth, img.naturalHeight));
                  const canvas = document.createElement('canvas');
                  canvas.width = Math.max(1, Math.round(img.naturalWidth * scale));
                  canvas.height = Math.max(1, Math.round(img.naturalHeight * scale));
                  canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
                  return canvas.toDataURL('image/jpeg', 0.72);
                } catch (_) { return null; }
              };
              Promise.all(unique.map(async function(item) {
                const media = (await Promise.all(item.images.map(imageData))).filter(Boolean);
                return {external_id: item.id, text: item.text, telegram_url: item.link,
                  posted_at: item.posted_at, media_data_urls: media};
              })).then(function(messages) {
                const titleNode = document.querySelector('.chat-info .peer-title, .topbar .peer-title, header [title]');
                window.TelegramNotifBridge.postMessages(JSON.stringify({
                  source: source,
                  channel_title: titleNode ? (titleNode.textContent || titleNode.getAttribute('title')) : source,
                  messages: messages
                }));
              });
              return true;
            })();
        """.trimIndent()
        webView?.evaluateJavascript(script, null)
    }

    private inner class WebBridge {
        @JavascriptInterface
        fun postMessages(payload: String) {
            if (!running) return
            executor.execute { ingestPayload(payload) }
        }
    }

    private fun ingestPayload(payload: String) {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val source = root.optString("source").trim().removePrefix("@")
        if (source.isBlank()) return
        val messages = root.optJSONArray("messages") ?: JSONArray()
        val seen = settings.webSeenKeys.toMutableSet()
        val sourcePrefix = "$source:"
        val hasBaseline = seen.any { it.startsWith(sourcePrefix) }
        val candidates = buildList {
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                val externalId = message.optString("external_id")
                if (externalId.isBlank()) continue
                add(message to "$sourcePrefix$externalId")
            }
        }
        if (!hasBaseline) {
            candidates.forEach { seen.add(it.second) }
            persistSeen(seen)
            return
        }
        val client = ApiClient(settings)
        for ((message, key) in candidates) {
            if (key in seen) continue
            val request = JSONObject()
                .put("source", source)
                .put("external_id", message.optString("external_id"))
                .put("text", message.optString("text"))
                .put("channel_title", root.optString("channel_title", source))
                .put("media_data_urls", message.optJSONArray("media_data_urls") ?: JSONArray())
            message.optString("telegram_url").takeIf { it.isNotBlank() && it != "null" }
                ?.let { request.put("telegram_url", it) }
            message.optString("posted_at").takeIf { it.isNotBlank() && it != "null" }
                ?.let { request.put("posted_at", it) }
            if (runCatching { client.ingestWebMessage(request) }.isSuccess) {
                seen.add(key)
            }
        }
        persistSeen(seen)
    }

    private fun persistSeen(values: MutableSet<String>) {
        if (values.size > MAX_SEEN_KEYS) {
            values.toList().take(values.size - MAX_SEEN_KEYS).forEach(values::remove)
        }
        settings.webSeenKeys = values
    }

    companion object {
        private const val BRIDGE_NAME = "TelegramNotifBridge"
        private const val PAGE_SETTLE_MILLIS = 6_000L
        private const val CHANNEL_WINDOW_MILLIS = 14_000L
        private const val CYCLE_PAUSE_MILLIS = 20_000L
        private const val EMPTY_RETRY_MILLIS = 30_000L
        private const val MAX_SEEN_KEYS = 1_500
    }
}
