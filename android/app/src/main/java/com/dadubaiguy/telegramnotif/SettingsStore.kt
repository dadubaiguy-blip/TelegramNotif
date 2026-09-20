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
