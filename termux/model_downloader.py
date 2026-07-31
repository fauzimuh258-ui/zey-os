import gzip
import logging
import logging.handlers
import os
import shutil
import subprocess
import sys
import time

import requests

from cache_manager import ModelCacheManager

OLLAMA_API = "http://127.0.0.1:11434"

# Primary target -> ordered fallback chain (smaller footprint each step)
TARGET_MODELS = {
    "tinyllama": ["tinyllama:1.1b-chat-v1.0-q4_0", "tinyllama:1.1b-chat-v1.0-q2_K"],
    "gemma2b": ["gemma:2b-instruct-q4_0", "gemma:2b-instruct-q3_K_M", "gemma:2b-instruct-q2_K"],
}

LOG_DIR = os.path.join(os.path.expanduser("~"), ".zeyos_logs")
os.makedirs(LOG_DIR, exist_ok=True)


def _gzip_rotator(source, dest):
    with open(source, "rb") as sf, gzip.open(dest, "wb") as df:
        shutil.copyfileobj(sf, df)
    os.remove(source)


handler = logging.handlers.RotatingFileHandler(
    os.path.join(LOG_DIR, "model_downloader.log"), maxBytes=256 * 1024, backupCount=3
)
handler.rotator = _gzip_rotator
handler.namer = lambda name: name + ".gz"
logging.basicConfig(level=logging.INFO, handlers=[handler, logging.StreamHandler()],
                     format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("zeyos.downloader")

cache = ModelCacheManager()


def notify(title, message):
    """Best-effort push notification via Termux:API. No-ops if not installed."""
    try:
        subprocess.run(
            ["termux-notification", "--title", title, "--content", message],
            timeout=5, capture_output=True,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass


def check_storage_min_bytes(required_bytes):
    stat = shutil.disk_usage(os.path.expanduser("~"))
    return stat.free >= required_bytes


def is_ollama_running():
    try:
        r = requests.get(f"{OLLAMA_API}/api/tags", timeout=3)
        return r.status_code == 200
    except requests.exceptions.RequestException:
        return False


def delete_model(model_tag):
    try:
        r = requests.delete(f"{OLLAMA_API}/api/delete", json={"name": model_tag}, timeout=15)
        return r.status_code == 200
    except requests.exceptions.RequestException:
        return False


def _pull_once(model_tag):
    url = f"{OLLAMA_API}/api/pull"
    response = requests.post(url, json={"name": model_tag, "stream": False}, timeout=600)
    if response.status_code != 200:
        raise RuntimeError(f"HTTP {response.status_code}")


def download_with_retry(model_tag, max_retries=3, base_delay=5):
    """Exponential backoff retry for a single model tag."""
    delay = base_delay
    for attempt in range(1, max_retries + 1):
        try:
            logger.info(f"Pulling {model_tag} (attempt {attempt}/{max_retries})")
            _pull_once(model_tag)
            logger.info(f"Download completed for {model_tag}")
            cache.touch_model(model_tag)
            return True
        except (requests.exceptions.RequestException, RuntimeError) as e:
            logger.warning(f"Attempt {attempt} failed for {model_tag}: {e}")
            if attempt < max_retries:
                time.sleep(delay)
                delay *= 2
    return False


def download_model(model_key, min_free_gb):
    """Tries each fallback tag in order; frees space via LRU eviction if needed."""
    tags = TARGET_MODELS[model_key]
    required_bytes = min_free_gb * 1024 * 1024 * 1024

    if not check_storage_min_bytes(required_bytes):
        logger.warning(f"Below {min_free_gb}GB free — attempting auto-cleanup before {model_key}")
        lru = cache.least_recently_used()
        if lru and delete_model(lru):
            logger.info(f"Auto-cleanup freed space by removing {lru}")
            cache.forget_model(lru)
        if not check_storage_min_bytes(required_bytes):
            logger.error(f"Cannot download {model_key}: still below {min_free_gb}GB after cleanup.")
            notify("Zey OS — Download Failed", f"Not enough storage for {model_key}.")
            return False

    for tag in tags:
        if download_with_retry(tag):
            notify("Zey OS — Model Ready", f"{tag} downloaded and ready.")
            return True
        logger.warning(f"Falling back from {tag} to next quantization level, if any.")

    logger.error(f"All fallback tags exhausted for {model_key}.")
    notify("Zey OS — Download Failed", f"Could not download any variant of {model_key}.")
    return False


if __name__ == "__main__":
    if not is_ollama_running():
        logger.error("Ollama server is not reachable on localhost:11434. Start serve script first.")
        sys.exit(1)

    download_model("tinyllama", min_free_gb=2)
    download_model("gemma2b", min_free_gb=3)
