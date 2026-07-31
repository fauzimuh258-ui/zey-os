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
LOG_DIR = os.path.join(os.path.expanduser("~"), ".zeyos_logs")
os.makedirs(LOG_DIR, exist_ok=True)


def _gzip_rotator(source, dest):
    with open(source, "rb") as sf, gzip.open(dest, "wb") as df:
        shutil.copyfileobj(sf, df)
    os.remove(source)


handler = logging.handlers.RotatingFileHandler(
    os.path.join(LOG_DIR, "memory_optimizer.log"), maxBytes=256 * 1024, backupCount=3
)
handler.rotator = _gzip_rotator
handler.namer = lambda name: name + ".gz"
logging.basicConfig(level=logging.INFO, handlers=[handler, logging.StreamHandler()],
                     format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("zeyos.optimizer")


def notify(title, message):
    try:
        subprocess.run(
            ["termux-notification", "--title", title, "--content", message],
            timeout=5, capture_output=True,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired):
        pass


class MemoryOptimizer:
    def __init__(self, swap_size_mb=2048, max_temp_celsius=65.0, min_free_storage_gb=1.5):
        self.swap_size_mb = swap_size_mb
        self.max_temp_celsius = max_temp_celsius
        self.min_free_storage_gb = min_free_storage_gb
        self.swap_file = os.path.join(os.path.expanduser("~"), "zeyos_swap.img")
        self.cache = ModelCacheManager()

    def setup_storage_swap(self, max_retries=3):
        """Creates and activates virtual storage swap if not active. Retries transient failures."""
        if os.path.exists(self.swap_file):
            logger.info("Virtual swap file already exists.")
            return True

        logger.info(f"Allocating {self.swap_size_mb}MB virtual storage swap...")
        for attempt in range(1, max_retries + 1):
            try:
                subprocess.run(
                    ["dd", "if=/dev/zero", f"of={self.swap_file}", "bs=1M", f"count={self.swap_size_mb}"],
                    check=True, capture_output=True,
                )
                subprocess.run(["mkswap", self.swap_file], check=True, capture_output=True)
                subprocess.run(["swapon", self.swap_file], check=True, capture_output=True)
                logger.info("Storage swap successfully created and mounted.")
                return True
            except subprocess.CalledProcessError as e:
                logger.warning(f"Swap setup attempt {attempt}/{max_retries} failed: {e}")
                if os.path.exists(self.swap_file):
                    os.remove(self.swap_file)  # Don't leave a half-written swap file behind
                time.sleep(2 * attempt)

        logger.error("Failed to set up storage swap after retries. Continuing without it.")
        notify("Zey OS — Swap Setup Failed", "Continuing without virtual swap; low-RAM risk is higher.")
        return False

    def get_thermal_status(self):
        thermal_paths = [
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
        ]
        for path in thermal_paths:
            if os.path.exists(path):
                try:
                    with open(path, "r") as f:
                        val = float(f.read().strip())
                        return val / 1000.0 if val > 1000 else val
                except (OSError, ValueError):
                    continue
        return 0.0

    def enforce_thermal_throttle(self):
        current_temp = self.get_thermal_status()
        if current_temp >= self.max_temp_celsius:
            logger.warning(f"High thermal detected: {current_temp}C >= {self.max_temp_celsius}C")
            notify("Zey OS — Thermal Throttle", f"CPU at {current_temp}C. Reducing threads.")
            os.environ["OLLAMA_CPU_THREADS"] = "2"
            time.sleep(3)
            return True
        os.environ["OLLAMA_CPU_THREADS"] = "4"
        return False

    def trigger_oom_recovery(self):
        logger.critical("Memory threshold breached. Triggering OOM auto-unload recovery.")
        notify("Zey OS — Critical Memory", "Unloading model to prevent an OOM kill.")
        try:
            requests.post(f"{OLLAMA_API}/api/generate", json={"model": "", "keep_alive": 0}, timeout=2)
        except requests.exceptions.RequestException:
            subprocess.run(["pkill", "-9", "-f", "ollama"], capture_output=True)
            logger.warning("Process force-killed to prevent system freeze.")

    def check_storage_and_cleanup(self):
        """Performance/storage hygiene: evict the least-recently-used model when
        free storage drops below the configured floor."""
        free_gb = shutil.disk_usage(os.path.expanduser("~")).free / (1024 ** 3)
        if free_gb >= self.min_free_storage_gb:
            return
        lru = self.cache.least_recently_used()
        if not lru:
            return
        logger.warning(f"Free storage {free_gb:.2f}GB below floor — evicting LRU model {lru}")
        try:
            r = requests.delete(f"{OLLAMA_API}/api/delete", json={"name": lru}, timeout=15)
            if r.status_code == 200:
                self.cache.forget_model(lru)
                logger.info(f"Evicted {lru} to reclaim storage.")
        except requests.exceptions.RequestException as e:
            logger.error(f"Could not evict {lru}: {e}")

    def run_optimization_loop(self):
        self.setup_storage_swap()
        logger.info("Optimization loop started.")
        while True:
            try:
                self.enforce_thermal_throttle()
                self.check_storage_and_cleanup()

                with open("/proc/meminfo", "r") as f:
                    mem_free = 0
                    for line in f:
                        if "MemAvailable" in line:
                            mem_free = int(line.split()[1]) // 1024
                            break

                if mem_free < 200:
                    self.trigger_oom_recovery()

            except Exception as e:
                logger.error(f"Unexpected error in optimization loop: {e}")

            time.sleep(5)


if __name__ == "__main__":
    optimizer = MemoryOptimizer()
    optimizer.run_optimization_loop()
