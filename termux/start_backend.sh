#!/data/data/com.termux/files/usr/bin/bash

# Started by the Android APK through Termux's RUN_COMMAND service.
# Progress is sent back to the app as explicit broadcasts and retained in this log.
set -u

PROJECT_DIR="${TELEGRAMNOTIF_PROJECT_DIR:-$HOME/TelegramNotif}"
LOG_DIR="$PROJECT_DIR/data"
LOG_FILE="$LOG_DIR/termux-backend.log"
PID_FILE="$LOG_DIR/termux-backend.pid"
PROGRESS_ACTION="com.dadubaiguy.telegramnotif.SETUP_PROGRESS"
PROGRESS_PACKAGE="com.dadubaiguy.telegramnotif"
SETUP_STARTED_AT="${TELEGRAMNOTIF_SETUP_STARTED_AT:-$(date +%s)}"
CURRENT_PROGRESS=28

if [ ! -d "$PROJECT_DIR" ]; then
    echo "Project folder not found: $PROJECT_DIR"
    exit 1
fi

cd "$PROJECT_DIR" || exit 1
mkdir -p "$LOG_DIR"
exec >>"$LOG_FILE" 2>&1

emit_progress() {
    local percent="$1"
    local phase="$2"
    local message="${3:-}"
    local now elapsed eta
    now="$(date +%s)"
    elapsed=$((now - SETUP_STARTED_AT))
    if [ "$percent" -gt 1 ] && [ "$percent" -lt 100 ]; then
        eta=$((elapsed * (100 - percent) / percent))
    else
        eta=0
    fi
    /system/bin/am broadcast --user 0 \
        -a "$PROGRESS_ACTION" \
        -p "$PROGRESS_PACKAGE" \
        --ei percent "$percent" \
        --ei elapsed_seconds "$elapsed" \
        --ei eta_seconds "$eta" \
        --es phase "$phase" \
        --es log "${message:0:280}" \
        >/dev/null 2>&1 || true
}

announce() {
    local percent="$1"
    local phase="$2"
    local message="$3"
    CURRENT_PROGRESS="$percent"
    echo "[$(date)] $message"
    emit_progress "$percent" "$phase" "$message"
}

run_logged() {
    local from="$1"
    local to="$2"
    local phase="$3"
    shift 3
    CURRENT_PROGRESS="$from"
    emit_progress "$from" "$phase" "Running: $*"
    "$@" 2>&1 | tr '\r' '\n' | while IFS= read -r line || [ -n "$line" ]; do
        local percent="$from"
        if [[ "$line" =~ ([0-9]{1,3})% ]]; then
            local inner="${BASH_REMATCH[1]}"
            if [ "$inner" -le 100 ]; then
                percent=$((from + (to - from) * inner / 100))
            fi
        fi
        echo "$line"
        emit_progress "$percent" "$phase" "$line"
    done
    local command_status="${PIPESTATUS[0]}"
    if [ "$command_status" -eq 0 ]; then
        CURRENT_PROGRESS="$to"
        emit_progress "$to" "$phase" "$phase complete"
    fi
    return "$command_status"
}

fail_setup() {
    local message="$1"
    echo "[$(date)] ERROR: $message"
    emit_progress "$CURRENT_PROGRESS" "Setup failed" "$message"
    exit 1
}

announce 28 "Updating" "Checking the repository for updates"
if [ -d ".git" ]; then
    OLD_COMMIT="$(git rev-parse HEAD 2>/dev/null || true)"
    run_logged 28 35 "Downloading update" git fetch --progress origin main \
        || fail_setup "Could not download the repository update"
    if ! run_logged 35 38 "Applying update" git merge --ff-only origin/main; then
        announce 38 "Applying update" "Update skipped because tracked files have local changes"
    fi
    NEW_COMMIT="$(git rev-parse HEAD 2>/dev/null || true)"
else
    OLD_COMMIT=""
    NEW_COMMIT=""
    announce 38 "Updating" "Repository is ready"
fi

if ! command -v python >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1; then
    run_logged 38 46 "Refreshing packages" pkg update -y \
        || fail_setup "Termux package refresh failed"
    run_logged 46 58 "Installing Python" pkg install -y python git \
        || fail_setup "Python installation failed"
else
    announce 58 "Checking Python" "Python and Git are already installed"
fi

if [ ! -f ".env" ] && [ -f ".env.example" ]; then
    cp ".env.example" ".env"
    announce 60 "Creating configuration" "Created .env; Telegram credentials still need to be entered once"
else
    announce 60 "Checking configuration" "Local configuration is preserved"
fi

if [ ! -x ".venv/bin/python" ]; then
    run_logged 60 68 "Creating Python environment" python -m venv .venv \
        || fail_setup "Could not create the Python environment"
else
    announce 68 "Checking Python environment" "Python environment is already available"
fi

DEPENDENCY_HASH="$(sha256sum pyproject.toml | cut -d ' ' -f 1)"
INSTALLED_HASH="$(cat .venv/.telegramnotif_dependency_hash 2>/dev/null || true)"
if [ "$DEPENDENCY_HASH" != "$INSTALLED_HASH" ]; then
    run_logged 68 94 "Downloading dependencies" \
        .venv/bin/python -m pip install --progress-bar on -e . \
        || fail_setup "Python dependency installation failed"
    echo "$DEPENDENCY_HASH" >".venv/.telegramnotif_dependency_hash"
else
    announce 94 "Checking dependencies" "Python dependencies are already current"
fi

if [ -f "$PID_FILE" ]; then
    RUNNING_PID="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [ -n "$RUNNING_PID" ] && kill -0 "$RUNNING_PID" 2>/dev/null; then
        if [ "$OLD_COMMIT" = "$NEW_COMMIT" ]; then
            announce 100 "Ready" "Backend is already running as PID $RUNNING_PID"
            exit 0
        fi
        announce 95 "Restarting backend" "Repository updated; restarting backend PID $RUNNING_PID"
        kill "$RUNNING_PID" 2>/dev/null || true
        sleep 1
    fi
fi

if command -v termux-wake-lock >/dev/null 2>&1; then
    termux-wake-lock || true
fi

announce 96 "Starting backend" "Starting the local API"
nohup .venv/bin/python -m uvicorn app.main:app \
    --host 127.0.0.1 \
    --port 8000 \
    </dev/null >>"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"

for attempt in $(seq 1 15); do
    if .venv/bin/python -c \
        "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/health', timeout=1)" \
        >/dev/null 2>&1; then
        announce 100 "Ready" "Backend is online as PID $(cat "$PID_FILE")"
        exit 0
    fi
    percent=$((96 + attempt * 3 / 15))
    emit_progress "$percent" "Starting backend" "Waiting for the local API (${attempt}/15)"
    sleep 1
done

announce 100 "Ready" "Backend process started; the health check is still warming up"
