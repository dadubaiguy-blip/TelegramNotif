#!/data/data/com.termux/files/usr/bin/bash

# This script is started by the Android APK through Termux's RUN_COMMAND service.
# It assumes the repository has already been cloned and .env has been configured.
set -u

PROJECT_DIR="${TELEGRAMNOTIF_PROJECT_DIR:-$HOME/TelegramNotif}"
LOG_DIR="$PROJECT_DIR/data"
LOG_FILE="$LOG_DIR/termux-backend.log"
PID_FILE="$LOG_DIR/termux-backend.pid"

mkdir -p "$LOG_DIR"
exec >>"$LOG_FILE" 2>&1
echo "[$(date)] TelegramNotif startup requested"

if [ ! -d "$PROJECT_DIR" ]; then
    echo "Project folder not found: $PROJECT_DIR"
    echo "Clone TelegramNotif there and configure .env before enabling automatic startup."
    exit 1
fi

cd "$PROJECT_DIR" || exit 1

if ! command -v python >/dev/null 2>&1; then
    pkg update -y || exit 1
    pkg install -y python git || exit 1
fi

if [ ! -x ".venv/bin/python" ]; then
    python -m venv .venv || exit 1
fi

if [ ! -f ".venv/.telegramnotif_dependencies_ready" ]; then
    .venv/bin/python -m pip install -e . || exit 1
    touch ".venv/.telegramnotif_dependencies_ready"
fi

if [ -f "$PID_FILE" ]; then
    RUNNING_PID="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [ -n "$RUNNING_PID" ] && kill -0 "$RUNNING_PID" 2>/dev/null; then
        echo "Backend already running as PID $RUNNING_PID"
        exit 0
    fi
fi

if command -v termux-wake-lock >/dev/null 2>&1; then
    termux-wake-lock || true
fi

nohup .venv/bin/python -m uvicorn app.main:app \
    --host 127.0.0.1 \
    --port 8000 \
    </dev/null >>"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"
echo "Backend started as PID $(cat "$PID_FILE")"
