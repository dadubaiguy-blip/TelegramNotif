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
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    private const val REPOSITORY_URL = "https://github.com/dadubaiguy-blip/TelegramNotif.git"

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
        val quotedProject = shellQuote(projectPath)
        val quotedRepository = shellQuote(REPOSITORY_URL)
        val quotedBash = shellQuote(TERMUX_BASH)
        val bootstrap = "set -e; PROJECT_DIR=$quotedProject; " +
            "if ! command -v git >/dev/null 2>&1; then pkg update -y && pkg install -y git; fi; " +
            "if [ ! -d \"\$PROJECT_DIR/.git\" ]; then " +
            "mkdir -p \"\$(dirname \"\$PROJECT_DIR\")\" && " +
            "git clone --depth 1 $quotedRepository \"\$PROJECT_DIR\"; fi; " +
            "exec $quotedBash \"\$PROJECT_DIR/termux/start_backend.sh\""
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setPackage(TERMUX_PACKAGE)
            putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", bootstrap))
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_RUNNER, "app-shell")
            putExtra(EXTRA_COMMAND_LABEL, "TelegramNotif backend")
            putExtra(EXTRA_COMMAND_DESCRIPTION, "Start the local TelegramNotif backend")
        }
        context.startService(intent)
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    fun openTermux(context: Context): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(TERMUX_PACKAGE)
            ?: return false
        context.startActivity(launchIntent)
        return true
    }
}
