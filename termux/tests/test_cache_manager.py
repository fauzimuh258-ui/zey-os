import time
import pytest

from cache_manager import ModelCacheManager


@pytest.fixture
def cache(tmp_path):
    return ModelCacheManager(cache_file=str(tmp_path / "cache.json"), ttl=300)


def test_touch_and_lru_order(cache):
    cache.touch_model("model_a")
    time.sleep(0.02)
    cache.touch_model("model_b")
    time.sleep(0.02)
    cache.touch_model("model_c")

    assert cache.least_recently_used() == "model_a"
    assert cache.least_recently_used(exclude={"model_a"}) == "model_b"


def test_retouch_moves_model_out_of_lru_position(cache):
    cache.touch_model("model_a")
    time.sleep(0.02)
    cache.touch_model("model_b")
    cache.touch_model("model_a")  # re-touch bumps it forward

    assert cache.least_recently_used() == "model_b"


def test_forget_model_removes_it(cache):
    cache.touch_model("model_a")
    cache.touch_model("model_b")
    cache.forget_model("model_a")

    assert cache.least_recently_used() == "model_b"


def test_forget_missing_model_is_a_noop(cache):
    cache.touch_model("model_a")
    cache.forget_model("does_not_exist")  # must not raise

    assert cache.least_recently_used() == "model_a"


def test_least_recently_used_returns_none_when_empty(cache):
    assert cache.least_recently_used() is None


def test_auto_cleanup_keeps_only_the_specified_model(cache):
    cache.touch_model("keep_me")
    cache.touch_model("evict_1")
    cache.touch_model("evict_2")

    removed = cache.auto_cleanup(keep_model="keep_me", delete_fn=lambda tag: True)

    assert set(removed) == {"evict_1", "evict_2"}
    assert cache.least_recently_used() == "keep_me"


def test_auto_cleanup_skips_models_where_delete_fn_fails(cache):
    cache.touch_model("keep_me")
    cache.touch_model("stubborn")

    removed = cache.auto_cleanup(keep_model="keep_me", delete_fn=lambda tag: False)

    assert removed == []
    assert cache.least_recently_used(exclude={"keep_me"}) == "stubborn"


def test_cache_persists_across_instances(tmp_path):
    cache_file = str(tmp_path / "cache.json")
    cache1 = ModelCacheManager(cache_file=cache_file, ttl=300)
    cache1.touch_model("persisted_model")

    cache2 = ModelCacheManager(cache_file=cache_file, ttl=300)
    assert "persisted_model" in cache2._data["models"]


def test_is_stale_reflects_ttl(tmp_path):
    c = ModelCacheManager(cache_file=str(tmp_path / "c2.json"), ttl=0.05)
    assert c.is_stale() is True  # never checked yet

    c.record_check(["a", "b"])
    assert c.is_stale() is False

    time.sleep(0.1)
    assert c.is_stale() is True


def test_get_cached_installed_returns_none_when_stale(tmp_path):
    c = ModelCacheManager(cache_file=str(tmp_path / "c3.json"), ttl=0.05)
    c.record_check(["a"])
    assert c.get_cached_installed() == ["a"]

    time.sleep(0.1)
    assert c.get_cached_installed() is None


def test_corrupted_cache_file_falls_back_to_empty(tmp_path):
    cache_file = tmp_path / "bad.json"
    cache_file.write_text("not valid json{{{")

    c = ModelCacheManager(cache_file=str(cache_file), ttl=300)
    assert c.least_recently_used() is None  # started clean, didn't crash
