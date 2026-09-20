package com.dadubaiguy.telegramnotif

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
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
    private enum class FeedFilter { ALL, GAMES, ACCOUNTS, URGENT }
    private enum class StatusState { NEUTRAL, GOOD, ERROR }

    private lateinit var settings: SettingsStore
    private lateinit var statusDot: TextView
    private lateinit var statusText: TextView
    private lateinit var monitorButton: TextView
    private lateinit var resultCount: TextView
    private lateinit var notificationList: LinearLayout

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
    private lateinit var termuxPathInput: EditText
    private lateinit var termuxAutoStartCheck: CheckBox
    private lateinit var termuxStatusText: TextView

    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val filterChips = mutableMapOf<FeedFilter, TextView>()
    private var latestItems: List<ListingNotification> = emptyList()
    private var selectedFilter = FeedFilter.ALL
    private var pendingNotificationId: Int? = null
    private var settingsDialog: Dialog? = null
    private var settingsStatusText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        pendingNotificationId = savedInstanceState?.getInt(EXTRA_NOTIFICATION_ID)
            ?: intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1).takeIf { it > 0 }
        buildMainUi()
        requestNotificationPermission()
        updateMonitorButton()
        if (settings.termuxAutoStart) startTermuxBackend(silent = true)
        if (settings.autoStart) ensureMonitorService()
        refreshListings()
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
        settingsDialog?.dismiss()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildMainUi() {
        window.statusBarColor = palette("#FFFFFF")
        window.navigationBarColor = palette("#FFFFFF")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette("#F6F7F9"))
        }
        root.addView(buildHeader())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(34))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        body.addView(buildStatusCard())
        body.addView(label("Browse", 13f, palette("#667085"), true), marginParams(top = 22))
        body.addView(buildFilterRow(), marginParams(top = 8))

        val feedHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        feedHeader.addView(label("Latest listings", 21f, palette("#182230"), true), LinearLayout.LayoutParams(0, -2, 1f))
        resultCount = label("0 listings", 12f, palette("#667085"))
        feedHeader.addView(resultCount)
        body.addView(feedHeader, marginParams(top = 24))

        notificationList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(notificationList, marginParams(top = 8))
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.WHITE)
            elevation = dp(1).toFloat()
        }
        val mark = label("TN", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(palette("#315EFB"), 12)
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(42), dp(42)))

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titleBlock.addView(label("TelegramNotif", 19f, palette("#182230"), true))
        titleBlock.addView(label("Smart listing alerts", 12f, palette("#667085")))
        header.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))

        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            contentDescription = "Open settings"
            background = rounded(Color.WHITE, 12, palette("#E4E7EC"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { showSettingsDialog() }
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        return header
    }

    private fun buildStatusCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = rounded(Color.WHITE, 16, palette("#E4E7EC"))
        }
        statusDot = label("●", 15f, palette("#F79009"), true)
        card.addView(statusDot)
        statusText = label("Connecting…", 13f, palette("#475467"), true).apply {
            setPadding(dp(8), 0, dp(8), 0)
        }
        card.addView(statusText, LinearLayout.LayoutParams(0, -2, 1f))

        val refresh = ImageButton(this).apply {
            setImageResource(R.drawable.ic_refresh)
            contentDescription = "Refresh listings"
            background = rounded(palette("#F8FAFC"), 10)
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { refreshListings() }
        }
        card.addView(refresh, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(7) })
        monitorButton = actionButton("Start alerts", primary = true) { toggleMonitor() }
        card.addView(monitorButton, LinearLayout.LayoutParams(-2, dp(40)))
        return card
    }

    private fun buildFilterRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val entries = listOf(
            FeedFilter.ALL to "All",
            FeedFilter.GAMES to "Games",
            FeedFilter.ACCOUNTS to "Accounts",
            FeedFilter.URGENT to "Urgent",
        )
        entries.forEachIndexed { index, (filter, title) ->
            val chip = label(title, 12f, palette("#475467"), true).apply {
                gravity = Gravity.CENTER
                setOnClickListener {
                    selectedFilter = filter
                    updateFilterChips()
                    renderNotifications()
                }
            }
            filterChips[filter] = chip
            row.addView(
                chip,
                LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                    if (index > 0) marginStart = dp(7)
                },
            )
        }
        updateFilterChips()
        return row
    }

    private fun updateFilterChips() {
        filterChips.forEach { (filter, view) ->
            val selected = filter == selectedFilter
            view.setTextColor(if (selected) palette("#214FD6") else palette("#475467"))
            view.background = rounded(
                if (selected) palette("#EEF3FF") else Color.WHITE,
                11,
                if (selected) palette("#AFC6FF") else palette("#E4E7EC"),
            )
        }
    }

    private fun showSettingsDialog() {
        settingsDialog?.dismiss()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        settingsDialog = dialog

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.WHITE, 20)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerCopy.addView(label("Settings", 22f, palette("#182230"), true))
        headerCopy.addView(label("Connection, AI and alert preferences", 12f, palette("#667085")))
        header.addView(headerCopy, LinearLayout.LayoutParams(0, -2, 1f))
        val close = label("×", 30f, palette("#475467"), false).apply {
            gravity = Gravity.CENTER
            contentDescription = "Close settings"
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(42), dp(42)))
        shell.addView(header)

        val scroll = ScrollView(this).apply { clipToPadding = false }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(18))
        }
        scroll.addView(form)
        shell.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val connection = settingsSection("Connection", "Where the Android app finds your backend")
        serverInput = input("Backend URL", settings.serverUrl)
        connection.addView(serverInput, marginParams(top = 9))
        connection.addView(actionButton("Test connection", false) { testConnection() }, marginParams(top = 8))
        form.addView(connection, marginParams(top = 8))

        val ai = settingsSection("GapGPT", "Choose the text and image models used for listings")
        apiKeyInput = input("GapGPT API key", settings.apiKey, password = true)
        textModelInput = input("Text model ID", settings.textModel)
        visionModelInput = input("Vision model ID", settings.visionModel)
        ai.addView(apiKeyInput, marginParams(top = 9))
        ai.addView(textModelInput, marginParams(top = 8))
        ai.addView(visionModelInput, marginParams(top = 8))
        visionCheck = checkBox("Analyze images before text extraction", settings.visionEnabled)
        ai.addView(visionCheck, marginParams(top = 5))
        ai.addView(actionButton("Load available models", false) { loadModels() }, marginParams(top = 6))
        modelChoices = label("", 11f, palette("#667085"))
        ai.addView(modelChoices, marginParams(top = 5))
        form.addView(ai, marginParams(top = 10))

        val notifications = settingsSection("Notifications", "Control filtering and what happens when you tap")
        onlyPricedCheck = checkBox("Notify only when AI finds a price", settings.onlyPriced)
        notifications.addView(onlyPricedCheck, marginParams(top = 7))
        notifications.addView(label("Notification tap action", 12f, palette("#667085"), true), marginParams(top = 7))
        clickTargetGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val openApp = radioButton("Open listing inside TelegramNotif", VIEW_APP_ID)
        val openTelegram = radioButton("Open the original Telegram message", TELEGRAM_ID)
        clickTargetGroup.addView(openApp)
        clickTargetGroup.addView(openTelegram)
        clickTargetGroup.check(if (settings.clickTarget == "telegram") TELEGRAM_ID else VIEW_APP_ID)
        notifications.addView(clickTargetGroup)
        form.addView(notifications, marginParams(top = 10))

        val channels = settingsSection("Channels", "Public usernames and private channels joined by the backend account")
        val channelActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        channelActions.addView(actionButton("Find joined", false) { findJoinedChannels() }, LinearLayout.LayoutParams(0, dp(42), 1f))
        channelActions.addView(actionButton("Refresh", false) { loadChannels() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(7) })
        channels.addView(channelActions, marginParams(top = 9))
        channelInput = input("Username or numeric channel ID", "")
        channels.addView(channelInput, marginParams(top = 8))
        channels.addView(actionButton("Add channel", false) { addChannel() }, marginParams(top = 7))
        channelsText = label("Loading channels…", 11f, palette("#667085"))
        channels.addView(channelsText, marginParams(top = 8))
        form.addView(channels, marginParams(top = 10))

        val localBackend = settingsSection("Local backend", "Termux can clone, update and run the public repository on this phone")
        termuxPathInput = input("Termux project folder", settings.termuxProjectPath)
        localBackend.addView(termuxPathInput, marginParams(top = 9))
        termuxAutoStartCheck = checkBox("Start and update through Termux when this app opens", settings.termuxAutoStart)
        localBackend.addView(termuxAutoStartCheck, marginParams(top = 5))
        val termuxActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        termuxActions.addView(actionButton("Start now", false) { startTermuxBackend(false) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        termuxActions.addView(actionButton("Open Termux", false) { openTermux() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(7) })
        localBackend.addView(termuxActions, marginParams(top = 7))
        termuxStatusText = label(
            if (settings.termuxAutoStart) "Automatic local startup is enabled" else "Automatic local startup is off",
            11f,
            palette("#667085"),
        )
        localBackend.addView(termuxStatusText, marginParams(top = 7))
        form.addView(localBackend, marginParams(top = 10))

        val dialogStatus = label("", 11f, palette("#667085")).apply { gravity = Gravity.CENTER }
        settingsStatusText = dialogStatus
        shell.addView(dialogStatus, marginParams(top = 5))
        shell.addView(actionButton("Save changes", true) { saveSettings(dialog) }, marginParams(top = 8))

        dialog.setContentView(shell)
        dialog.setOnDismissListener {
            settingsStatusText = null
            if (settingsDialog === dialog) settingsDialog = null
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.42f }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setLayout(
                (resources.displayMetrics.widthPixels * 0.94f).roundToInt(),
                (resources.displayMetrics.heightPixels * 0.90f).roundToInt(),
            )
        }
        loadChannels()
    }

    private fun testConnection() {
        saveLocalFields()
        status("Testing connection…", StatusState.NEUTRAL)
        executor.execute {
            try {
                val health = ApiClient(settings).health()
                val telegram = health.optJSONObject("telegram")?.optBoolean("configured", false) == true
                runOnUiThread {
                    status(
                        if (telegram) "Connected • Telegram ready" else "Connected • Telegram needs setup",
                        if (telegram) StatusState.GOOD else StatusState.NEUTRAL,
                    )
                }
            } catch (error: Exception) {
                runOnUiThread { status("Connection failed: ${shortError(error)}", StatusState.ERROR) }
            }
        }
    }

    private fun saveSettings(dialog: Dialog) {
        saveLocalFields()
        status("Saving settings…", StatusState.NEUTRAL)
        executor.execute {
            try {
                val client = ApiClient(settings)
                client.updateAiSettings(settings.apiKey, settings.textModel, settings.visionModel, settings.visionEnabled)
                client.updateNotificationSettings(settings.onlyPriced, settings.clickTarget)
                runOnUiThread {
                    status("Settings saved", StatusState.GOOD)
                    dialog.dismiss()
                    refreshListings()
                }
            } catch (error: Exception) {
                runOnUiThread { status("Saved on phone; backend unavailable: ${shortError(error)}", StatusState.ERROR) }
            }
        }
    }

    private fun loadModels() {
        saveLocalFields()
        status("Loading model list…", StatusState.NEUTRAL)
        executor.execute {
            try {
                val models = ApiClient(settings).getModels()
                runOnUiThread {
                    modelChoices.text = if (models.isEmpty()) "No models returned" else models.joinToString("  •  ")
                    if (models.size == 1 && textModelInput.text.isBlank()) textModelInput.setText(models.first())
                    status("Loaded ${models.size} model(s)", StatusState.GOOD)
                }
            } catch (error: Exception) {
                runOnUiThread { status("Model list failed: ${shortError(error)}", StatusState.ERROR) }
            }
        }
    }

    private fun refreshListings() {
        status("Refreshing listings…", StatusState.NEUTRAL)
        executor.execute {
            try {
                val items = ApiClient(settings).getNotifications(unreadOnly = false)
                runOnUiThread {
                    latestItems = items.take(100)
                    renderNotifications()
                    pendingNotificationId?.let { id ->
                        items.firstOrNull { it.id == id }?.let(::showDetails)
                        pendingNotificationId = null
                    }
                    status("Connected • ${items.size} listings", StatusState.GOOD)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    latestItems = emptyList()
                    renderNotifications()
                    status("Backend unavailable • ${shortError(error)}", StatusState.ERROR)
                }
            }
        }
    }

    private fun renderNotifications() {
        notificationList.removeAllViews()
        val filtered = latestItems.filter { item ->
            when (selectedFilter) {
                FeedFilter.ALL -> true
                FeedFilter.GAMES -> item.parsed.optString("category") == "game"
                FeedFilter.ACCOUNTS -> item.parsed.optString("category") == "account"
                FeedFilter.URGENT -> item.urgent
            }
        }
        resultCount.text = "${filtered.size} ${if (filtered.size == 1) "listing" else "listings"}"
        if (filtered.isEmpty()) {
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(34), dp(22), dp(34))
                background = rounded(Color.WHITE, 16, palette("#E4E7EC"))
            }
            empty.addView(label("Nothing here yet", 16f, palette("#344054"), true).apply { gravity = Gravity.CENTER })
            empty.addView(label("New matching Telegram listings will appear here.", 12f, palette("#667085")).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            })
            notificationList.addView(empty, marginParams(top = 6))
            return
        }
        filtered.forEach { item -> notificationList.addView(buildListingCard(item), marginParams(top = 9)) }
    }

    private fun buildListingCard(item: ListingNotification): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(Color.WHITE, 16, palette("#E4E7EC"))
            isClickable = true
            isFocusable = true
            setOnClickListener { showDetails(item) }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(smallChip("@${item.source}", palette("#EEF3FF"), palette("#214FD6")))
        if (item.urgent) {
            top.addView(
                smallChip("WATCHLIST", palette("#FFF1F0"), palette("#C4320A")),
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) },
            )
        }
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(label(cleanDate(item.createdAt), 10f, palette("#98A2B3")))
        card.addView(top)
        card.addView(label(item.title, 17f, palette("#182230"), true), marginParams(top = 10))
        if (item.body.isNotBlank()) card.addView(label(item.body, 13f, palette("#475467")), marginParams(top = 5))

        item.mediaUrls.firstOrNull()?.let { url ->
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Listing image"
                background = rounded(palette("#EAECF0"), 12)
                clipToOutline = true
                setImageResource(android.R.drawable.ic_menu_gallery)
            }
            card.addView(image, LinearLayout.LayoutParams(-1, dp(156)).apply { topMargin = dp(11) })
            executor.execute {
                val bitmap = runCatching { ApiClient(settings).fetchBitmap(url) }.getOrNull()
                if (bitmap != null) runOnUiThread { image.setImageBitmap(bitmap) }
            }
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val mediaLabel = when {
            item.mediaUrls.size > 1 -> "${item.mediaUrls.size} images"
            item.mediaUrls.size == 1 -> "1 image"
            else -> "Text listing"
        }
        footer.addView(label(mediaLabel, 11f, palette("#667085")), LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(label("View details  ›", 12f, palette("#315EFB"), true))
        card.addView(footer)
        return card
    }

    private fun showDetails(item: ListingNotification) {
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        detail.addView(label(item.title, 20f, palette("#182230"), true))
        detail.addView(label("@${item.source} • ${cleanDate(item.createdAt)}", 11f, palette("#667085")), marginParams(top = 4))
        if (item.body.isNotBlank()) detail.addView(label(item.body, 14f, palette("#344054")), marginParams(top = 12))
        if (item.text.isNotBlank()) detail.addView(label(item.text, 13f, palette("#475467")), marginParams(top = 10))
        detail.addView(label(formatParsed(item.parsed), 12f, palette("#475467")), marginParams(top = 12))
        item.mediaUrls.forEachIndexed { index, url ->
            val image = ImageView(this).apply {
                adjustViewBounds = true
                minimumHeight = dp(120)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Listing image ${index + 1}"
                background = rounded(palette("#EAECF0"), 12)
                clipToOutline = true
                setImageResource(android.R.drawable.ic_menu_gallery)
            }
            detail.addView(image, marginParams(top = 10))
            executor.execute {
                val bitmap = runCatching { ApiClient(settings).fetchBitmap(url) }.getOrNull()
                if (bitmap != null) runOnUiThread { image.setImageBitmap(bitmap) }
            }
        }
        detail.addView(actionButton("Open in Telegram", true) { openTelegram(item) }, marginParams(top = 12))
        val scroll = ScrollView(this).apply { addView(detail) }
        AlertDialog.Builder(this).setView(scroll).setPositiveButton("Close", null).show()
        executor.execute { runCatching { ApiClient(settings).markNotificationRead(item.id) } }
    }

    private fun formatParsed(parsed: JSONObject): String {
        val names = jsonStrings(parsed.optJSONArray("item_names"))
        val contacts = jsonStrings(parsed.optJSONArray("contact_handles"))
        val price = parsed.optString("price").takeIf { it.isNotBlank() && it != "null" } ?: "Not listed"
        return "Items: ${names.ifEmpty { listOf("Not identified") }.joinToString(", ")}\n" +
            "Price: $price\nAvailability: ${parsed.optString("availability", "unknown")}\n" +
            "DM: ${contacts.ifEmpty { listOf("No contact found") }.joinToString(", ")}"
    }

    private fun findJoinedChannels() {
        saveLocalFields()
        status("Finding joined channels…", StatusState.NEUTRAL)
        executor.execute {
            try {
                val joined = ApiClient(settings).getJoinedChannels()
                runOnUiThread {
                    if (joined.isEmpty()) {
                        status("No joined channels found", StatusState.ERROR)
                        return@runOnUiThread
                    }
                    val labels = joined.map { "${it.displayName} (${it.source})" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Add a joined channel")
                        .setItems(labels) { _, index -> addJoinedChannel(joined[index]) }
                        .setNegativeButton("Cancel", null)
                        .show()
                    status("Found ${joined.size} joined channels", StatusState.GOOD)
                }
            } catch (error: Exception) {
                runOnUiThread { status("Channel lookup failed: ${shortError(error)}", StatusState.ERROR) }
            }
        }
    }

    private fun addJoinedChannel(channel: ChannelInfo) {
        executor.execute {
            runCatching { ApiClient(settings).addChannel(channel.source, channel.displayName) }
                .onSuccess { runOnUiThread { loadChannels(); status("Channel added", StatusState.GOOD) } }
                .onFailure { runOnUiThread { status("Channel failed: ${shortError(it)}", StatusState.ERROR) } }
        }
    }

    private fun addChannel() {
        saveLocalFields()
        val source = channelInput.text.toString().trim()
        if (source.isBlank()) {
            toast("Enter a channel username or numeric ID")
            return
        }
        executor.execute {
            try {
                ApiClient(settings).addChannel(source)
                runOnUiThread {
                    channelInput.text.clear()
                    loadChannels()
                    status("Channel added", StatusState.GOOD)
                }
            } catch (error: Exception) {
                runOnUiThread { status("Channel failed: ${shortError(error)}", StatusState.ERROR) }
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
                    channelsText.text = if (channels.isEmpty()) "No channels configured" else channels.joinToString("\n") { "• ${it.displayName} — ${it.source}" }
                }
            } catch (error: Exception) {
                runOnUiThread { channelsText.text = "Channels unavailable: ${shortError(error)}" }
            }
        }
    }

    private fun startTermuxBackend(silent: Boolean) {
        saveLocalFields()
        if (!TermuxBridge.isInstalled(this)) {
            termuxStatus("Termux is not installed. Tap Open Termux to install it.")
            if (!silent) toast("Install Termux first")
            return
        }
        termuxStatus("Sending clone/update/start command to Termux…")
        executor.execute {
            try {
                TermuxBridge.launch(this, settings.termuxProjectPath)
                runOnUiThread {
                    termuxStatus("Command sent. The first setup can take a few minutes.")
                    status("Starting local backend…", StatusState.NEUTRAL)
                    if (!silent) toast("Termux startup command sent")
                    window.decorView.postDelayed({ refreshListings() }, 8_000)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    termuxStatus("Termux rejected the command. Enable allow-external-apps=true.")
                    status("Termux start failed: ${shortError(error)}", StatusState.ERROR)
                }
            }
        }
    }

    private fun openTermux() {
        if (TermuxBridge.openTermux(this)) return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")))
    }

    private fun toggleMonitor() {
        if (settings.autoStart) stopAlerts() else startAlerts()
    }

    private fun startAlerts() {
        saveLocalFields()
        settings.autoStart = true
        if (!settings.monitorInitialized) settings.clearMonitorCursor()
        if (settings.termuxAutoStart) startTermuxBackend(silent = true)
        ensureMonitorService()
        updateMonitorButton()
        status("Background alerts are on", StatusState.GOOD)
    }

    private fun ensureMonitorService() {
        val serviceIntent = Intent(this, NotificationMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent) else startService(serviceIntent)
    }

    private fun stopAlerts() {
        settings.autoStart = false
        stopService(Intent(this, NotificationMonitorService::class.java))
        updateMonitorButton()
        status("Background alerts are paused", StatusState.NEUTRAL)
    }

    private fun updateMonitorButton() {
        if (!::monitorButton.isInitialized) return
        monitorButton.text = if (settings.autoStart) "Pause" else "Start alerts"
        monitorButton.background = rounded(if (settings.autoStart) palette("#F2F4F7") else palette("#315EFB"), 10)
        monitorButton.setTextColor(if (settings.autoStart) palette("#344054") else Color.WHITE)
    }

    private fun openTelegram(item: ListingNotification) {
        val url = item.telegramUrl
        if (url.isNullOrBlank()) {
            toast("This listing has no Telegram link")
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun saveLocalFields() {
        if (settingsDialog?.isShowing != true || !::serverInput.isInitialized) return
        settings.serverUrl = serverInput.text.toString().ifBlank { SettingsStore.DEFAULT_SERVER_URL }
        settings.apiKey = apiKeyInput.text.toString()
        settings.textModel = textModelInput.text.toString()
        settings.visionModel = visionModelInput.text.toString()
        settings.visionEnabled = visionCheck.isChecked
        settings.onlyPriced = onlyPricedCheck.isChecked
        settings.clickTarget = if (clickTargetGroup.checkedRadioButtonId == TELEGRAM_ID) "telegram" else "app"
        settings.termuxProjectPath = termuxPathInput.text.toString().ifBlank { SettingsStore.DEFAULT_TERMUX_PROJECT_PATH }
        settings.termuxAutoStart = termuxAutoStartCheck.isChecked
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
    }

    private fun settingsSection(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(13), dp(12), dp(13), dp(13))
        background = rounded(palette("#F8FAFC"), 14, palette("#E4E7EC"))
        addView(label(title, 15f, palette("#182230"), true))
        addView(label(subtitle, 11f, palette("#667085")), marginParams(top = 2))
    }

    private fun input(hint: String, value: String, password: Boolean = false): EditText = EditText(this).apply {
        setHint(hint)
        setText(value)
        textSize = 13f
        setTextColor(palette("#182230"))
        setHintTextColor(palette("#98A2B3"))
        setSingleLine(true)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
        background = rounded(Color.WHITE, 10, palette("#D0D5DD"))
        setPadding(dp(12), 0, dp(12), 0)
        minHeight = dp(46)
    }

    private fun checkBox(title: String, checked: Boolean): CheckBox = CheckBox(this).apply {
        text = title
        textSize = 12f
        setTextColor(palette("#344054"))
        isChecked = checked
        buttonTintList = ColorStateList.valueOf(palette("#315EFB"))
    }

    private fun radioButton(title: String, viewId: Int): RadioButton = RadioButton(this).apply {
        id = viewId
        text = title
        textSize = 12f
        setTextColor(palette("#344054"))
        buttonTintList = ColorStateList.valueOf(palette("#315EFB"))
    }

    private fun actionButton(title: String, primary: Boolean, action: () -> Unit): TextView =
        label(title, 12f, if (primary) Color.WHITE else palette("#344054"), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(13), 0, dp(13), 0)
            minHeight = dp(42)
            background = rounded(if (primary) palette("#315EFB") else Color.WHITE, 10, if (primary) null else palette("#D0D5DD"))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun smallChip(text: String, fill: Int, textColor: Int): TextView =
        label(text, 10f, textColor, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(fill, 20)
        }

    private fun label(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun marginParams(top: Int = 0, start: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(start), dp(top), 0, 0) }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun status(value: String, state: StatusState) {
        if (::statusText.isInitialized) statusText.text = value
        if (::statusDot.isInitialized) {
            statusDot.setTextColor(
                when (state) {
                    StatusState.GOOD -> palette("#12B76A")
                    StatusState.ERROR -> palette("#F04438")
                    StatusState.NEUTRAL -> palette("#F79009")
                },
            )
        }
        settingsStatusText?.text = value
    }

    private fun termuxStatus(value: String) {
        if (::termuxStatusText.isInitialized && settingsDialog?.isShowing == true) termuxStatusText.text = value
    }

    private fun jsonStrings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun cleanDate(value: String): String {
        if (value.isBlank()) return "Now"
        return value.replace('T', ' ').substringBefore('.').take(16)
    }

    private fun shortError(error: Throwable): String = error.message?.lineSequence()?.firstOrNull()?.take(90) ?: "unknown error"
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun palette(value: String): Int = Color.parseColor(value)

    companion object {
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val VIEW_APP_ID = 1001
        private const val TELEGRAM_ID = 1002
        private const val NOTIFICATION_PERMISSION_REQUEST = 44
    }
}
