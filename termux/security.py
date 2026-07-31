import functools
import hmac
import os
import secrets
import threading
import time

API_KEY_FILE = os.path.join(os.path.expanduser("~"), ".zeyos_api_key")


def get_or_create_api_key():
    """Persists a per-device API key with owner-only file permissions."""
    if os.path.exists(API_KEY_FILE):
        with open(API_KEY_FILE, "r") as f:
            key = f.read().strip()
            if key:
                return key
    key = "zos_" + secrets.token_urlsafe(32)
    with open(API_KEY_FILE, "w") as f:
        f.write(key)
    os.chmod(API_KEY_FILE, 0o600)
    return key


def constant_time_compare(a, b):
    if not a or not b:
        return False
    return hmac.compare_digest(a, b)


class TokenBucketRateLimiter:
    """Thread-safe token bucket. Mirrors the Kotlin RateLimiter in the Android app."""

    def __init__(self, capacity=10, refill_per_second=5.0):
        self.capacity = capacity
        self.refill_per_second = refill_per_second
        self.tokens = float(capacity)
        self.last_refill = time.monotonic()
        self.lock = threading.Lock()

    def try_acquire(self):
        with self.lock:
            now = time.monotonic()
            elapsed = now - self.last_refill
            if elapsed > 0:
                self.tokens = min(self.capacity, self.tokens + elapsed * self.refill_per_second)
                self.last_refill = now
            if self.tokens >= 1.0:
                self.tokens -= 1.0
                return True
            return False


def require_api_key(expected_key):
    """Flask route decorator enforcing X-API-Key header auth."""
    def decorator(view_func):
        @functools.wraps(view_func)
        def wrapper(*args, **kwargs):
            from flask import request, jsonify
            provided = request.headers.get("X-API-Key", "")
            if not constant_time_compare(provided, expected_key):
                return jsonify({"error": "unauthorized"}), 401
            return view_func(*args, **kwargs)
        return wrapper
    return decorator


def rate_limited(limiter: TokenBucketRateLimiter):
    """Flask route decorator enforcing a shared token bucket."""
    def decorator(view_func):
        @functools.wraps(view_func)
        def wrapper(*args, **kwargs):
            from flask import jsonify
            if not limiter.try_acquire():
                return jsonify({"error": "rate_limited"}), 429
            return view_func(*args, **kwargs)
        return wrapper
    return decorator
