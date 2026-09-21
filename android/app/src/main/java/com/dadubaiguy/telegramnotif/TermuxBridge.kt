package com.dadubaiguy.telegramnotif

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object TermuxBridge {
    const val ACTION_SETUP_PROGRESS = "com.dadubaiguy.telegramnotif.SETUP_PROGRESS"
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
        val quotedPackage = shellQuote(context.packageName)
        val quotedAction = shellQuote(ACTION_SETUP_PROGRESS)
        val bootstrap = """
            set -u
            PROJECT_DIR=$quotedProject
            REPOSITORY=$quotedRepository
            ACTION=$quotedAction
            PACKAGE=$quotedPackage
            STARTED=§(date +%s)
            CURRENT=2
            emit() {
                local percent="§1" phase="§2" message="§{3:-}"
                local elapsed=§((§(date +%s) - STARTED)) eta=0
                if [ "§percent" -gt 1 ] && [ "§percent" -lt 100 ]; then
                    eta=§((elapsed * (100 - percent) / percent))
                fi
                /system/bin/am broadcast --user 0 -a "§ACTION" -p "§PACKAGE" \
                    --ei percent "§percent" --ei elapsed_seconds "§elapsed" \
                    --ei eta_seconds "§eta" --es phase "§phase" \
                    --es log "§{message:0:280}" >/dev/null 2>&1 || true
            }
            run_logged() {
                local from="§1" to="§2" phase="§3"
                shift 3
                CURRENT="§from"
                emit "§from" "§phase" "Running: §*"
                "§@" 2>&1 | tr '\r' '\n' | while IFS= read -r line || [ -n "§line" ]; do
                    local percent="§from"
                    if [[ "§line" =~ ([0-9]{1,3})% ]]; then
                        local inner="§{BASH_REMATCH[1]}"
                        if [ "§inner" -le 100 ]; then
                            percent=§((from + (to - from) * inner / 100))
                        fi
                    fi
                    emit "§percent" "§phase" "§line"
                done
                local code="§{PIPESTATUS[0]}"
                if [ "§code" -eq 0 ]; then
                    CURRENT="§to"
                    emit "§to" "§phase" "§phase complete"
                fi
                return "§code"
            }
            fail() {
                emit "§CURRENT" "Setup failed" "§1"
                exit 1
            }
            emit 2 "Preparing Termux" "Starting local setup"
            if ! command -v git >/dev/null 2>&1; then
                run_logged 3 9 "Refreshing packages" pkg update -y \
                    || fail "Termux package refresh failed"
                run_logged 9 15 "Installing Git" pkg install -y git \
                    || fail "Git installation failed"
            else
                emit 15 "Checking Git" "Git is already installed"
            fi
            if [ ! -d "§PROJECT_DIR/.git" ]; then
                mkdir -p "§(dirname "§PROJECT_DIR")"
                run_logged 15 28 "Cloning repository" \
                    git clone --progress --depth 1 "§REPOSITORY" "§PROJECT_DIR" \
                    || fail "Repository clone failed. Make sure it is public."
            else
                emit 28 "Checking repository" "Local repository found"
            fi
            export TELEGRAMNOTIF_SETUP_STARTED_AT="§STARTED"
            exec $quotedBash "§PROJECT_DIR/termux/start_backend.sh"
        """.trimIndent().replace('§', '$')
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

    fun launchTelegramLogin(context: Context, projectPath: String) {
        require(isInstalled(context)) { "Termux is not installed" }
        val command = "cd ${shellQuote(projectPath)} && .venv/bin/python scripts/auth_telegram.py"
        val intent = Intent(ACTION_RUN_COMMAND).apply {
            setPackage(TERMUX_PACKAGE)
            putExtra(EXTRA_COMMAND_PATH, TERMUX_BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-lc", command))
            putExtra(EXTRA_WORKDIR, TERMUX_HOME)
            putExtra(EXTRA_RUNNER, "terminal-session")
            putExtra(EXTRA_COMMAND_LABEL, "Telegram login")
            putExtra(
                EXTRA_COMMAND_DESCRIPTION,
                "Enter your phone number, Telegram code, and optional 2FA password",
            )
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
