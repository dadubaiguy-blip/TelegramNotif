package com.dadubaiguy.telegramnotif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class NotificationMonitorService : Service() {
    private lateinit var settings: SettingsStore
    private var scheduler: ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        createNotificationChannels()
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification())
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleWithFixedDelay(::pollBackend, 0, POLL_SECONDS, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        scheduler?.shutdownNow()
        scheduler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pollBackend() {
        val currentSettings = SettingsStore(this)
        val client = ApiClient(currentSettings)
        try {
            val pending = client.getNotifications(unreadOnly = true).sortedBy { it.id }
            if (!currentSettings.monitorInitialized) {
                // Do not flood the device with old alerts the first time monitoring is enabled.
                currentSettings.lastNotificationId = pending.maxOfOrNull { it.id } ?: 0
                currentSettings.monitorInitialized = true
                return
            }

            for (item in pending) {
                if (item.id <= currentSettings.lastNotificationId) continue
                showListingNotification(client, item)
                currentSettings.lastNotificationId = item.id
                runCatching { client.markNotificationRead(item.id) }
            }
        } catch (_: Exception) {
            // The foreground service retries on the next interval. The UI can test the backend.
        }
    }

    private fun showListingNotification(client: ApiClient, item: ListingNotification) {
        val openIntent = if (item.clickTarget == "telegram" && item.telegramUrl != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(item.telegramUrl))
        } else {
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_NOTIFICATION_ID, item.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            item.id,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val fullText = listOf(item.body, item.text.takeIf { it.isNotBlank() })
            .filterNotNull()
            .joinToString("\n")
        val builder = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(com.dadubaiguy.telegramnotif.R.drawable.ic_stat_notify)
            .setContentTitle(item.title)
            .setContentText(item.body)
            .setStyle(Notification.BigTextStyle().bigText(fullText))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)

        val firstImage = item.mediaUrls.firstOrNull()?.let { runCatching { client.fetchBitmap(it) }.getOrNull() }
        if (firstImage != null) {
            builder.setStyle(Notification.BigPictureStyle().bigPicture(firstImage))
        }
        val notification = builder.build()
        getSystemService(NotificationManager::class.java).notify(item.id, notification)
    }

    private fun serviceNotification(): Notification = Notification.Builder(this, SERVICE_CHANNEL_ID)
        .setSmallIcon(com.dadubaiguy.telegramnotif.R.drawable.ic_stat_notify)
        .setContentTitle(getString(R.string.app_name))
        .setContentText("Watching Telegram listings")
        .setOngoing(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL_ID,
                "TelegramNotif background service",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        alertChannel.description = getString(R.string.notification_channel_description)
        alertChannel.enableVibration(true)
        manager.createNotificationChannel(alertChannel)
    }

    companion object {
        private const val POLL_SECONDS = 15L
        private const val SERVICE_NOTIFICATION_ID = 11
        private const val SERVICE_CHANNEL_ID = "telegramnotif_service"
        private const val ALERT_CHANNEL_ID = "telegramnotif_alerts"
    }
}
