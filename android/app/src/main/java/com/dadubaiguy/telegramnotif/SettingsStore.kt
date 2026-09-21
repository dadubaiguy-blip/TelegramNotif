package com.dadubaiguy.telegramnotif

import android.content.Context

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("telegramnotif", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = preferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = preferences.edit().putString(KEY_SERVER_URL, value.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = preferences.getString(KEY_API_KEY, "") ?: ""
        set(value) = preferences.edit().putString(KEY_API_KEY, value).apply()

    var gapGptBaseUrl: String
        get() = preferences.getString(KEY_GAPGPT_BASE_URL, "") ?: ""
        set(value) = preferences.edit().putString(KEY_GAPGPT_BASE_URL, value.trim().trimEnd('/')).apply()

    var telegramApiId: String
        get() = preferences.getString(KEY_TELEGRAM_API_ID, "") ?: ""
        set(value) = preferences.edit().putString(KEY_TELEGRAM_API_ID, value.trim()).apply()

    var telegramApiHash: String
        get() = preferences.getString(KEY_TELEGRAM_API_HASH, "") ?: ""
        set(value) = preferences.edit().putString(KEY_TELEGRAM_API_HASH, value.trim()).apply()

    var telegramEnabled: Boolean
        get() = preferences.getBoolean(KEY_TELEGRAM_ENABLED, true)
        set(value) = preferences.edit().putBoolean(KEY_TELEGRAM_ENABLED, value).apply()

    var webModeEnabled: Boolean
        get() = preferences.getBoolean(KEY_WEB_MODE_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_WEB_MODE_ENABLED, value).apply()

    var webSeenKeys: Set<String>
        get() = preferences.getStringSet(KEY_WEB_SEEN_KEYS, emptySet())?.toSet() ?: emptySet()
        set(value) = preferences.edit().putStringSet(KEY_WEB_SEEN_KEYS, value.toSet()).apply()

    fun clearWebSeenKeys() {
        preferences.edit().remove(KEY_WEB_SEEN_KEYS).apply()
    }

    var aiTimeoutSeconds: String
        get() = preferences.getString(KEY_AI_TIMEOUT_SECONDS, "45") ?: "45"
        set(value) = preferences.edit().putString(KEY_AI_TIMEOUT_SECONDS, value.trim()).apply()

    var textModel: String
        get() = preferences.getString(KEY_TEXT_MODEL, "") ?: ""
        set(value) = preferences.edit().putString(KEY_TEXT_MODEL, value.trim()).apply()

    var visionModel: String
        get() = preferences.getString(KEY_VISION_MODEL, "") ?: ""
        set(value) = preferences.edit().putString(KEY_VISION_MODEL, value.trim()).apply()

    var visionEnabled: Boolean
        get() = preferences.getBoolean(KEY_VISION_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_VISION_ENABLED, value).apply()

    var onlyPriced: Boolean
        get() = preferences.getBoolean(KEY_ONLY_PRICED, true)
        set(value) = preferences.edit().putBoolean(KEY_ONLY_PRICED, value).apply()

    var clickTarget: String
        get() = preferences.getString(KEY_CLICK_TARGET, "app") ?: "app"
        set(value) = preferences.edit().putString(KEY_CLICK_TARGET, value).apply()

    var autoStart: Boolean
        get() = preferences.getBoolean(KEY_AUTO_START, false)
        set(value) = preferences.edit().putBoolean(KEY_AUTO_START, value).apply()

    var termuxAutoStart: Boolean
        get() = preferences.getBoolean(KEY_TERMUX_AUTO_START, true)
        set(value) = preferences.edit().putBoolean(KEY_TERMUX_AUTO_START, value).apply()

    var termuxProjectPath: String
        get() = preferences.getString(KEY_TERMUX_PROJECT_PATH, DEFAULT_TERMUX_PROJECT_PATH)
            ?: DEFAULT_TERMUX_PROJECT_PATH
        set(value) = preferences.edit().putString(KEY_TERMUX_PROJECT_PATH, value.trim().trimEnd('/')).apply()

    var setupCompleted: Boolean
        get() = preferences.getBoolean(KEY_SETUP_COMPLETED, false)
        set(value) = preferences.edit().putBoolean(KEY_SETUP_COMPLETED, value).apply()

    var monitorInitialized: Boolean
        get() = preferences.getBoolean(KEY_MONITOR_INITIALIZED, false)
        set(value) = preferences.edit().putBoolean(KEY_MONITOR_INITIALIZED, value).apply()

    var lastNotificationId: Int
        get() = preferences.getInt(KEY_LAST_NOTIFICATION_ID, 0)
        set(value) = preferences.edit().putInt(KEY_LAST_NOTIFICATION_ID, value).apply()

    fun clearMonitorCursor() {
        preferences.edit()
            .putBoolean(KEY_MONITOR_INITIALIZED, false)
            .putInt(KEY_LAST_NOTIFICATION_ID, 0)
            .apply()
    }

    companion object {
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:8000"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_GAPGPT_BASE_URL = "gapgpt_base_url"
        private const val KEY_TELEGRAM_API_ID = "telegram_api_id"
        private const val KEY_TELEGRAM_API_HASH = "telegram_api_hash"
        private const val KEY_TELEGRAM_ENABLED = "telegram_enabled"
        private const val KEY_WEB_MODE_ENABLED = "web_mode_enabled"
        private const val KEY_WEB_SEEN_KEYS = "web_seen_keys"
        private const val KEY_AI_TIMEOUT_SECONDS = "ai_timeout_seconds"
        private const val KEY_TEXT_MODEL = "text_model"
        private const val KEY_VISION_MODEL = "vision_model"
        private const val KEY_VISION_ENABLED = "vision_enabled"
        private const val KEY_ONLY_PRICED = "only_priced"
        private const val KEY_CLICK_TARGET = "click_target"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_TERMUX_AUTO_START = "termux_auto_start"
        private const val KEY_TERMUX_PROJECT_PATH = "termux_project_path"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_MONITOR_INITIALIZED = "monitor_initialized"
        private const val KEY_LAST_NOTIFICATION_ID = "last_notification_id"
        const val DEFAULT_TERMUX_PROJECT_PATH = "/data/data/com.termux/files/home/TelegramNotif"
    }
}
