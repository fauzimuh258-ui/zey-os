import json
import os
import time

CACHE_FILE = os.path.join(os.path.expanduser("~"), ".zeyos_model_cache.json")
CACHE_TTL_SECONDS = 300


class ModelCacheManager:
    """Tracks locally-pulled model metadata so downloader/optimizer scripts can
    avoid redundant Ollama API calls and make LRU eviction decisions without
    re-scanning the filesystem on every check."""

    def __init__(self, cache_file=CACHE_FILE, ttl=CACHE_TTL_SECONDS):
        self.cache_file = cache_file
        self.ttl = ttl
        self._data = self._load()

    def _load(self):
        if os.path.exists(self.cache_file):
            try:
                with open(self.cache_file, "r") as f:
                    data = json.load(f)
                    data.setdefault("models", {})
                    data.setdefault("checked_at", 0)
                    return data
            except (json.JSONDecodeError, OSError):
                pass
        return {"models": {}, "checked_at": 0}

    def _save(self):
        try:
            with open(self.cache_file, "w") as f:
                json.dump(self._data, f, indent=2)
        except OSError as e:
            print(f"[WARNING] Could not persist model cache: {e}")

    def is_stale(self):
        return (time.time() - self._data.get("checked_at", 0)) > self.ttl

    def touch_model(self, model_tag, size_bytes=None):
        existing = self._data["models"].get(model_tag, {})
        self._data["models"][model_tag] = {
            "last_used": time.time(),
            "size_bytes": size_bytes if size_bytes is not None else existing.get("size_bytes", 0),
        }
        self._save()

    def forget_model(self, model_tag):
        if model_tag in self._data["models"]:
            del self._data["models"][model_tag]
            self._save()

    def record_check(self, installed_models):
        self._data["checked_at"] = time.time()
        self._data["installed"] = installed_models
        self._save()

    def get_cached_installed(self):
        if self.is_stale():
            return None
        return self._data.get("installed")

    def least_recently_used(self, exclude=None):
        exclude = exclude or set()
        candidates = {tag: meta for tag, meta in self._data["models"].items() if tag not in exclude}
        if not candidates:
            return None
        return min(candidates.items(), key=lambda kv: kv[1]["last_used"])[0]

    def auto_cleanup(self, keep_model, delete_fn):
        """Evicts every cached model except `keep_model` via delete_fn(tag) -> bool."""
        removed = []
        for tag in list(self._data["models"].keys()):
            if tag == keep_model:
                continue
            if delete_fn(tag):
                del self._data["models"][tag]
                removed.append(tag)
        if removed:
            self._save()
        return removed
