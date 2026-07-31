#!/data/data/com.termux/files/usr/bin/bash
set -uo pipefail

LOG_FILE="$HOME/.zeyos_logs/serve.log"
mkdir -p "$HOME/.zeyos_logs"

log_info()  { echo "[ZEY OS] $*"  | tee -a "$LOG_FILE"; }
log_error() { echo "[ERROR] $*"   | tee -a "$LOG_FILE" >&2; }
notify() { command -v termux-notification &> /dev/null && termux-notification --title "Zey OS" --content "$1"; }

export OLLAMA_HOST="127.0.0.1:11434"
export OLLAMA_NUM_PARALLEL=1
export OLLAMA_MAX_LOADED_MODELS=1
export OLLAMA_KEEP_ALIVE="5m"
export OLLAMA_CPU_THREADS=4

log_info "Checking running instances..."
if pgrep -x "ollama" > /dev/null; then
    log_info "Ollama process is already running."
    exit 0
fi

log_info "Starting Ollama API service on ${OLLAMA_HOST}..."
nohup ollama serve >> "$HOME/.ollama.log" 2>&1 &

# Poll for readiness instead of a single fixed sleep — a process can exist
# without the HTTP server being ready to accept connections yet.
MAX_WAIT_SECONDS=30
elapsed=0
while [ "$elapsed" -lt "$MAX_WAIT_SECONDS" ]; do
    if curl -s -o /dev/null -m 2 "http://127.0.0.1:11434/api/tags"; then
        log_info "Engine successfully online after ${elapsed}s."
        notify "Zey OS engine online."
        exit 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
done

log_error "Failed to start Ollama within ${MAX_WAIT_SECONDS}s. Last 20 log lines:"
tail -n 20 "$HOME/.ollama.log" | tee -a "$LOG_FILE"
notify "Zey OS engine failed to start — check logs."
exit 1
