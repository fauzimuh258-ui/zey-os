#!/data/data/com.termux/files/usr/bin/bash

# Target Path: ~/.termux/boot/start_zeyos.sh (requires the Termux:Boot addon)

mkdir -p ~/.termux/boot ~/.zeyos_logs
LOGFILE="$HOME/.zeyos_logs/boot.log"

echo "[ZEY OS BOOT] Executing boot initialization at $(date)" >> "$LOGFILE"

# Acquire wake lock to prevent Android from deep-sleeping the process.
termux-wake-lock

export PATH="/data/data/com.termux/files/usr/bin:$PATH"

# Retry serve_ollama.sh up to 3 times — boot-time network/storage can be slow to settle.
attempt=1
max_attempts=3
until bash "$HOME/serve_ollama.sh" >> "$LOGFILE" 2>&1; do
    if [ "$attempt" -ge "$max_attempts" ]; then
        echo "[ZEY OS BOOT] serve_ollama.sh failed after $max_attempts attempts." >> "$LOGFILE"
        command -v termux-notification &> /dev/null && \
            termux-notification --title "Zey OS" --content "Boot start failed — open Termux to check logs."
        exit 1
    fi
    echo "[ZEY OS BOOT] Attempt $attempt failed, retrying in 10s..." >> "$LOGFILE"
    sleep 10
    attempt=$((attempt + 1))
done

# Start the secured proxy in front of Ollama (API key auth + rate limiting).
nohup python3 "$HOME/proxy_server.py" >> "$HOME/.zeyos_logs/proxy_boot.log" 2>&1 &

echo "[ZEY OS BOOT] Engine sequence initialized." >> "$LOGFILE"
