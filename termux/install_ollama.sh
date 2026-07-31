#!/data/data/com.termux/files/usr/bin/bash
set -uo pipefail  # Note: no -e — every risky step is wrapped in retry() with its own error handling

LOG_FILE="$HOME/.zeyos_logs/install.log"
mkdir -p "$HOME/.zeyos_logs"

log_info()  { echo "[ZEY OS] $*"     | tee -a "$LOG_FILE"; }
log_warn()  { echo "[WARNING] $*"    | tee -a "$LOG_FILE"; }
log_error() { echo "[ERROR] $*"      | tee -a "$LOG_FILE" >&2; }

notify() {
    command -v termux-notification &> /dev/null && termux-notification --title "Zey OS" --content "$1"
}

# Retries a command with exponential backoff. Usage: retry <max_attempts> <initial_delay_s> <cmd...>
retry() {
    local max_attempts="$1"; shift
    local delay="$1"; shift
    local attempt=1
    until "$@" >> "$LOG_FILE" 2>&1; do
        if [ "$attempt" -ge "$max_attempts" ]; then
            log_error "Command failed after $max_attempts attempts: $*"
            return 1
        fi
        log_warn "Attempt $attempt/$max_attempts failed: $*. Retrying in ${delay}s..."
        sleep "$delay"
        attempt=$((attempt + 1))
        delay=$((delay * 2))
    done
    return 0
}

# Permission/environment check — refuse to run outside Termux.
if [ ! -d "/data/data/com.termux/files/usr" ]; then
    log_error "This script must be run inside Termux. Aborting."
    exit 1
fi

log_info "Updating package repositories..."
if ! retry 3 5 pkg update -y; then
    log_error "pkg update failed repeatedly — check network connection."
    notify "Zey OS" "Install failed: no network for pkg update."
    exit 1
fi
retry 3 5 pkg upgrade -y || log_warn "pkg upgrade failed after retries, continuing with existing packages."

log_info "Installing core dependencies..."
if ! retry 3 5 pkg install -y curl wget git python procps proot; then
    log_error "Core dependency install failed repeatedly."
    notify "Zey OS" "Install failed: dependency install error."
    exit 1
fi

# Verification of available storage before proceeding
FREE_STORAGE_KB=$(df "$HOME" | tail -1 | awk '{print $4}')
REQUIRED_STORAGE_KB=5242880 # 5 GB in KB

if [ "$FREE_STORAGE_KB" -lt "$REQUIRED_STORAGE_KB" ]; then
    log_error "Insufficient storage! Free up at least 5GB on internal memory."
    notify "Zey OS" "Install failed: insufficient storage."
    exit 1
fi

log_info "Installing Ollama runtime..."
if ! command -v ollama &> /dev/null; then
    if ! retry 3 10 bash -c "curl -fsSL https://ollama.com/install.sh | sh"; then
        log_warn "Standard installer failed after retries — trying Go fallback..."
        if retry 2 5 pkg install -y golang && retry 2 10 go install github.com/ollama/ollama@latest; then
            log_info "Ollama installed via Go fallback."
        else
            log_error "Both installation paths failed. Aborting."
            notify "Zey OS" "Install failed: could not install Ollama."
            exit 1
        fi
    fi
else
    log_info "Ollama is already installed."
fi

log_info "Setup complete."
notify "Zey OS" "Installed successfully."
