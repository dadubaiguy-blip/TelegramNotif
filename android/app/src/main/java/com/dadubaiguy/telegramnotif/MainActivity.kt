package com.dadubaiguy.telegramnotif

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var settings: SettingsStore
    private lateinit var content: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var serverInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var textModelInput: EditText
    private lateinit var visionModelInput: EditText
    private lateinit var visionCheck: CheckBox
    private lateinit var onlyPricedCheck: CheckBox
    private lateinit var clickTargetGroup: RadioGroup
    private lateinit var modelChoices: TextView
    private lateinit var channelsText: TextView
    private lateinit var channelInput: EditText
    private lateinit var notificationList: LinearLayout
    private val executor: ExecutorService = Executors.newFixedThreadPool(3)
    private var pendingNotificationId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        pendingNotificationId = savedInstanceState?.getInt(EXTRA_NOTIFICATION_ID)
            ?: intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1).takeIf { it > 0 }
        buildUi()
        requestNotificationPermission()
        refreshListings()
        loadChannels()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingNotificationId = intent?.getIntExtra(EXTRA_NOTIFICATION_ID, -1)?.takeIf { it > 0 }
        refreshListings()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingNotificationId?.let { outState.putInt(EXTRA_NOTIFICATION_ID, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(32))
            setBackgroundColor(Color.rgb(246, 247, 251))
        }
        scroll.addView(content)
        setContentView(scroll)

        content.addView(label("TelegramNotif", 28f, Color.rgb(31, 36, 55), true))
        content.addView(label("Telegram listing alerts with an Android background monitor", 14f, Color.DKGRAY))
        statusText = label("Not connected yet", 14f, Color.rgb(75, 79, 96))
        content.addView(statusText, marginParams(top = 12))

        addSection("Backend")
        serverInput = input("Backend URL", settings.serverUrl)
        content.addView(serverInput, marginParams())
        content.addView(button("Test connection") { testConnection() }, marginParams(top = 8))
        content.addView(button("Refresh listings") { refreshListings() }, marginParams(top = 8))

        addSection("GapGPT")
        apiKeyInput = input("GapGPT API key", settings.apiKey, password = true)
        content.addView(apiKeyInput, marginParams())
        textModelInput = input("Text model ID", settings.textModel)
        content.addView(textModelInput, marginParams(top = 8))
        visionModelInput = input("Vision model ID (optional)", settings.visionModel)
        content.addView(visionModelInput, marginParams(top = 8))
        visionCheck = CheckBox(this).apply {
            text = "Analyze listing images before text extraction"
            isChecked = settings.visionEnabled
        }
        content.addView(visionCheck, marginParams(top = 4))
        content.addView(button("Load available models") { loadModels() }, marginParams(top = 4))
        modelChoices = label("Model list will appear here", 12f, Color.DKGRAY)
        content.addView(modelChoices, marginParams(top = 4))

        addSection("Notifications")
        onlyPricedCheck = CheckBox(this).apply {
            text = "Notify only when AI finds a price"
            isChecked = settings.onlyPriced
        }
        content.addView(onlyPricedCheck, marginParams())
        content.addView(label("When a notification is tapped:", 13f, Color.DKGRAY), marginParams(top = 4))
        clickTargetGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val openApp = RadioButton(this).apply {
            id = VIEW_APP_ID
            text = "Open the app"
        }
        val openTelegram = RadioButton(this).apply {
            id = TELEGRAM_ID
            text = "Open the Telegram message"
        }
        clickTargetGroup.addView(openApp)
        clickTargetGroup.addView(openTelegram)
        clickTargetGroup.check(if (settings.clickTarget == "telegram") TELEGRAM_ID else VIEW_APP_ID)
        content.addView(clickTargetGroup, marginParams(top = 2))
        content.addView(button("Save AI and notification settings") { saveSettings() }, marginParams(top = 8))

        addSection("Private channels")
        content.addView(label("Join private channels in Telegram first. The backend session must use the same account.", 12f, Color.DKGRAY))
        content.addView(button("Find channels I joined") { findJoinedChannels() }, marginParams(top = 8))
        channelInput = input("Username or numeric channel ID", "")
        content.addView(channelInput, marginParams(top = 8))
        content.addView(button("Add channel") { addChannel() }, marginParams(top = 8))
        channelsText = label("No channels loaded", 12f, Color.DKGRAY)
        content.addView(channelsText, marginParams(top = 8))

        addSection("Background alerts")
        content.addView(label("The foreground service polls the backend every 15 seconds and can show an image notification while the phone is asleep. Android notification and DND permissions still apply.", 12f, Color.DKGRAY))
        val alertButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        alertButtons.addView(button("Start alerts") { startAlerts() }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, dp(8), dp(4), 0) })
        alertButtons.addView(button("Stop") { stopAlerts() }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(4), dp(8), 0, 0) })
        content.addView(alertButtons)

        addSection("Recent notifications")
        notificationList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(notificationList)
    }

    private fun testConnection() {
        saveLocalFields()
        status("Testing backend…")
        executor.execute {
            try {
                val health = ApiClient(settings).health()
                val telegram = health.optJSONObject("telegram")?.optBoolean("configured", false) == true
                runOnUiThread { status("Connected. Telegram configured: $telegram") }
            } catch (error: Exception) {
                runOnUiThread { status("Connection failed: ${error.message ?: "unknown error"}") }
            }
        }
    }

    private fun saveSettings() {
        saveLocalFields()
        status("Saving settings…")
        executor.execute {
            try {
                val client = ApiClient(settings)
                client.updateAiSettings(
                    apiKey = settings.apiKey,
                    textModel = settings.textModel,
                    visionModel = settings.visionModel,
                    enableVision = settings.visionEnabled,
                )
                client.updateNotificationSettings(
                    onlyPriced = settings.onlyPriced,
                    clickTarget = settings.clickTarget,
                )
                runOnUiThread { status("Settings saved") }
            } catch (error: Exception) {
                runOnUiThread { status("Settings failed: ${error.message ?: "unknown error"}") }
            }
        }
    }

    private fun loadModels() {
        saveLocalFields()
        status("Loading models…")
        executor.execute {
            try {
                val models = ApiClient(settings).getModels()
                runOnUiThread {
                    modelChoices.text = if (models.isEmpty()) "No models returned" else models.joinToString("\n")
                    status("Loaded ${models.size} model(s). Paste or type the desired IDs above.")
                    if (models.size == 1 && textModelInput.text.isBlank()) textModelInput.setText(models.first())
                }
            } catch (error: Exception) {
                runOnUiThread { status("Model list failed: ${error.message ?: "unknown error"}") }
            }
        }
    }

    private fun refreshListings() {
        saveLocalFields()
        executor.execute {
            try {
                val items = ApiClient(settings).getNotifications(unreadOnly = false)
                runOnUiThread {
                    renderNotifications(items.take(50))
                    pendingNotificationId?.let { id ->
                        items.firstOrNull { it.id == id }?.let { showDetails(it) }
                        pendingNotificationId = null
                    }
                    status("Loaded ${items.size} notification(s)")
                }
            } catch (error: Exception) {
                runOnUiThread { status("Listings failed: ${error.message ?: "unknown error"}") }
            }
        }
    }

    private fun renderNotifications(items: List<ListingNotification>) {
        notificationList.removeAllViews()
        if (items.isEmpty()) {
            notificationList.addView(label("No notifications yet.", 14f, Color.DKGRAY), marginParams(top = 8))
            return
        }
        items.forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = roundedBackground(Color.WHITE)
                setOnClickListener { showDetails(item) }
            }
            card.addView(label(item.title, 16f, Color.rgb(31, 36, 55), true))
            card.addView(label(item.body, 13f, Color.DKGRAY), marginParams(top = 5))
            card.addView(label("@${item.source} · ${item.createdAt}", 11f, Color.GRAY), marginParams(top = 5))
            if (item.mediaUrls.isNotEmpty()) {
                val imageHint = label("${item.mediaUrls.size} image(s) — tap to open", 11f, Color.rgb(91, 75, 219))
                card.addView(imageHint, marginParams(top = 5))
            }
            notificationList.addView(card, marginParams(top = 8))
        }
    }

    private fun showDetails(item: ListingNotification) {
        val scroll = ScrollView(this)
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        detail.addView(label(item.title, 20f, Color.rgb(31, 36, 55), true))
        detail.addView(label(item.body, 14f, Color.DKGRAY), marginParams(top = 8))
        detail.addView(label(item.text, 13f, Color.DKGRAY), marginParams(top = 8))
        detail.addView(label(formatParsed(item.parsed), 13f, Color.DKGRAY), marginParams(top = 8))
        item.mediaUrls.forEachIndexed { index, url ->
            val image = ImageView(this).apply {
                adjustViewBounds = true
                minimumHeight = dp(100)
                contentDescription = "Listing image ${index + 1}"
                setImageResource(android.R.drawable.ic_menu_gallery)
            }
            detail.addView(image, marginParams(top = 8))
            executor.execute {
                val bitmap = runCatching { ApiClient(settings).fetchBitmap(url) }.getOrNull()
                if (bitmap != null) runOnUiThread { image.setImageBitmap(bitmap) }
            }
        }
        val openTelegram = button("Open this message in Telegram") { openTelegram(item) }
        detail.addView(openTelegram, marginParams(top = 10))
        scroll.addView(detail)
        AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
        executor.execute { runCatching { ApiClient(settings).markNotificationRead(item.id) } }
    }

    private fun formatParsed(parsed: JSONObject): String {
        val names = jsonStrings(parsed.optJSONArray("item_names"))
        val contacts = jsonStrings(parsed.optJSONArray("contact_handles"))
        val category = parsed.optString("category", "other")
        val availability = parsed.optString("availability", "unknown")
        val price = parsed.optString("price").takeIf { it.isNotBlank() && it != "null" }
            ?: "Price not listed"
        return "Games/items: ${names.ifEmpty { listOf("Not identified") }.joinToString(", ")}\n" +
            "Price: $price\n" +
            "Availability: $availability\n" +
            "Category: $category\n" +
            "DM: ${contacts.ifEmpty { listOf("No contact found") }.joinToString(", ")}"
    }

    private fun findJoinedChannels() {
        saveLocalFields()
        status("Finding joined channels…")
        executor.execute {
            try {
                val joined = ApiClient(settings).getJoinedChannels()
                runOnUiThread {
                    if (joined.isEmpty()) {
                        status("No joined channels returned. Check that the backend Telegram session is logged in.")
                        return@runOnUiThread
                    }
                    val labels = joined.map { "${it.displayName} (${it.source})" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Add a joined channel")
                        .setItems(labels) { _, index ->
                            executor.execute {
                                runCatching { ApiClient(settings).addChannel(joined[index].source, joined[index].displayName) }
                                    .onSuccess { runOnUiThread { loadChannels(); status("Channel added") } }
                                    .onFailure { error -> runOnUiThread { status("Channel failed: ${error.message}") } }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    status("Found ${joined.size} joined channel(s)")
                }
            } catch (error: Exception) {
                runOnUiThread { status("Private-channel lookup failed: ${error.message ?: "unknown error"}") }
            }
        }
    }

    private fun addChannel() {
        saveLocalFields()
        val source = channelInput.text.toString().trim()
        if (source.isBlank()) {
            toast("Enter a username or numeric channel ID")
            return
        }
        executor.execute {
            try {
                ApiClient(settings).addChannel(source)
                runOnUiThread {
                    channelInput.text.clear()
                    loadChannels()
                    status("Channel added")
                }
            } catch (error: Exception) {
                runOnUiThread { status("Channel failed: ${error.message ?: "unknown error"}") }
            }
        }
    }

    private fun loadChannels() {
        if (!::channelsText.isInitialized) return
        saveLocalFields()
        executor.execute {
            try {
                val channels = ApiClient(settings).getChannels()
                runOnUiThread {
                    channelsText.text = if (channels.isEmpty()) "No configured channels" else channels.joinToString("\n") { "• ${it.displayName} — ${it.source}" }
                }
            } catch (error: Exception) {
                runOnUiThread { channelsText.text = "Channels unavailable: ${error.message ?: "unknown error"}" }
            }
        }
    }

    private fun startAlerts() {
        saveLocalFields()
        settings.autoStart = true
        if (!settings.monitorInitialized) settings.clearMonitorCursor()
        val serviceIntent = Intent(this, NotificationMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
        status("Background alerts started")
    }

    private fun stopAlerts() {
        settings.autoStart = false
        stopService(Intent(this, NotificationMonitorService::class.java))
        status("Background alerts stopped")
    }

    private fun openTelegram(item: ListingNotification) {
        val url = item.telegramUrl
        if (url.isNullOrBlank()) {
            toast("This channel does not have a public Telegram link")
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun saveLocalFields() {
        if (!::serverInput.isInitialized) return
        settings.serverUrl = serverInput.text.toString().ifBlank { SettingsStore.DEFAULT_SERVER_URL }
        settings.apiKey = apiKeyInput.text.toString()
        settings.textModel = textModelInput.text.toString()
        settings.visionModel = visionModelInput.text.toString()
        settings.visionEnabled = visionCheck.isChecked
        settings.onlyPriced = onlyPricedCheck.isChecked
        settings.clickTarget = if (clickTargetGroup.checkedRadioButtonId == TELEGRAM_ID) "telegram" else "app"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    private fun addSection(title: String) {
        content.addView(label(title, 18f, Color.rgb(31, 36, 55), true), marginParams(top = 22))
    }

    private fun input(hint: String, value: String, password: Boolean = false): EditText = EditText(this).apply {
        setHint(hint)
        setText(value)
        textSize = 14f
        setSingleLine(true)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        setPadding(dp(12), dp(4), dp(12), dp(4))
    }

    private fun button(title: String, action: () -> Unit): Button = Button(this).apply {
        text = title
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun marginParams(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (top > 0) setMargins(0, dp(top), 0, 0)
        }

    private fun roundedBackground(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }

    private fun jsonStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList { for (index in 0 until array.length()) array.optString(index).takeIf { it.isNotBlank() }?.let(::add) }
    }

    private fun status(value: String) {
        if (::statusText.isInitialized) statusText.text = value
    }

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val VIEW_APP_ID = 1001
        private const val TELEGRAM_ID = 1002
        private const val NOTIFICATION_PERMISSION_REQUEST = 44
    }
}
