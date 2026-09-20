package com.dadubaiguy.telegramnotif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsStore(context)
        if (settings.termuxAutoStart && TermuxBridge.isInstalled(context)) {
            runCatching { TermuxBridge.launch(context, settings.termuxProjectPath) }
        }
        if (!settings.autoStart) return
        val serviceIntent = Intent(context, NotificationMonitorService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
