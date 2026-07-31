import os
import stat
import threading
import time

import security as sec
from security import TokenBucketRateLimiter, constant_time_compare, get_or_create_api_key


class TestConstantTimeCompare:
    def test_equal_strings_match(self):
        assert constant_time_compare("secret123", "secret123") is True

    def test_different_strings_do_not_match(self):
        assert constant_time_compare("secret123", "secret124") is False

    def test_empty_or_none_never_matches(self):
        assert constant_time_compare("", "secret") is False
        assert constant_time_compare("secret", "") is False
        assert constant_time_compare(None, "secret") is False
        assert constant_time_compare("secret", None) is False
        assert constant_time_compare(None, None) is False


class TestTokenBucketRateLimiter:
    def test_allows_requests_up_to_capacity(self):
        limiter = TokenBucketRateLimiter(capacity=3, refill_per_second=0.001)
        assert limiter.try_acquire() is True
        assert limiter.try_acquire() is True
        assert limiter.try_acquire() is True

    def test_blocks_once_capacity_is_exhausted(self):
        limiter = TokenBucketRateLimiter(capacity=2, refill_per_second=0.001)
        limiter.try_acquire()
        limiter.try_acquire()
        assert limiter.try_acquire() is False

    def test_refills_over_time(self):
        limiter = TokenBucketRateLimiter(capacity=1, refill_per_second=20.0)  # ~1 token/50ms
        assert limiter.try_acquire() is True
        assert limiter.try_acquire() is False
        time.sleep(0.1)
        assert limiter.try_acquire() is True

    def test_does_not_exceed_capacity_after_long_idle(self):
        limiter = TokenBucketRateLimiter(capacity=2, refill_per_second=100.0)
        time.sleep(0.2)  # would over-fill far past capacity if uncapped
        assert limiter.try_acquire() is True
        assert limiter.try_acquire() is True
        assert limiter.try_acquire() is False

    def test_thread_safety_under_concurrent_access(self):
        limiter = TokenBucketRateLimiter(capacity=50, refill_per_second=0.001)
        results = []
        results_lock = threading.Lock()

        def worker():
            acquired = limiter.try_acquire()
            with results_lock:
                results.append(acquired)

        threads = [threading.Thread(target=worker) for _ in range(200)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        # With effectively no refill, exactly `capacity` should succeed — a
        # non-atomic implementation would over- or under-count under the race.
        assert results.count(True) == 50


class TestApiKeyPersistence:
    def test_creates_and_persists_a_key(self, tmp_path, monkeypatch):
        key_file = tmp_path / "api_key"
        monkeypatch.setattr(sec, "API_KEY_FILE", str(key_file))

        key1 = get_or_create_api_key()
        assert key1.startswith("zos_")
        assert key_file.exists()

        key2 = get_or_create_api_key()
        assert key1 == key2  # second call reads the persisted key, doesn't regenerate

    def test_key_file_has_owner_only_permissions(self, tmp_path, monkeypatch):
        key_file = tmp_path / "api_key"
        monkeypatch.setattr(sec, "API_KEY_FILE", str(key_file))

        get_or_create_api_key()
        mode = stat.S_IMODE(os.stat(key_file).st_mode)
        assert mode == 0o600
