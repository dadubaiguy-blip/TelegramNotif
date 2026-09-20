package com.dadubaiguy.telegramnotif

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object TermuxBridge {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
    private const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"

    fun isInstalled(context: Context): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getApplicationInfo(
                TERMUX_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(TERMUX_PACKAGE, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun launch(context: Context, projectPath: String) {
        require(isInstalled(context)) { "Termux is not installed" }
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setPackage(TERMUX_PACKAGE)
            putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("termux/start_backend.sh"))
            putExtra(EXTRA_WORKDIR, projectPath)
            putExtra(EXTRA_RUNNER, "app-shell")
            putExtra(EXTRA_COMMAND_LABEL, "TelegramNotif backend")
            putExtra(EXTRA_COMMAND_DESCRIPTION, "Start the local TelegramNotif backend")
        }
        context.startService(intent)
    }

    fun openTermux(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            ?: return false
        context.startActivity(launchIntent)
        return true
    }
}
