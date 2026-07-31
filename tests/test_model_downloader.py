from unittest.mock import patch, MagicMock

import requests

import model_downloader as md


class TestDownloadWithRetry:
    def test_succeeds_on_first_attempt(self):
        with patch("model_downloader.requests.post") as mock_post:
            mock_post.return_value = MagicMock(status_code=200)
            assert md.download_with_retry("fake:tag", max_retries=3, base_delay=0.01) is True
            assert mock_post.call_count == 1

    def test_succeeds_after_transient_failures(self):
        responses = [requests.exceptions.ConnectionError("down"), MagicMock(status_code=200)]
        with patch("model_downloader.requests.post", side_effect=responses):
            assert md.download_with_retry("fake:tag", max_retries=3, base_delay=0.01) is True

    def test_gives_up_after_max_retries(self):
        with patch("model_downloader.requests.post",
                    side_effect=requests.exceptions.ConnectionError("down")) as mock_post:
            assert md.download_with_retry("fake:tag", max_retries=3, base_delay=0.01) is False
            assert mock_post.call_count == 3

    def test_non_200_response_is_treated_as_failure(self):
        with patch("model_downloader.requests.post") as mock_post:
            mock_post.return_value = MagicMock(status_code=500)
            assert md.download_with_retry("fake:tag", max_retries=2, base_delay=0.01) is False
            assert mock_post.call_count == 2

    def test_touches_cache_on_success(self):
        with patch("model_downloader.requests.post") as mock_post, \
             patch.object(md.cache, "touch_model") as mock_touch:
            mock_post.return_value = MagicMock(status_code=200)
            md.download_with_retry("fake:tag", max_retries=3, base_delay=0.01)
            mock_touch.assert_called_once_with("fake:tag")


class TestDownloadModel:
    def test_tries_fallback_tags_in_order_on_repeated_failure(self):
        attempted = []

        def record_and_fail(*args, **kwargs):
            attempted.append(kwargs.get("json", {}).get("name"))
            raise requests.exceptions.ConnectionError("down")

        with patch("model_downloader.requests.post", side_effect=record_and_fail), \
             patch("model_downloader.check_storage_min_bytes", return_value=True), \
             patch("model_downloader.time.sleep"), \
             patch("model_downloader.notify"):
            result = md.download_model("gemma2b", min_free_gb=3)

        assert result is False
        assert list(dict.fromkeys(attempted)) == md.TARGET_MODELS["gemma2b"]

    def test_stops_at_first_successful_fallback_tag(self):
        call_sequence = []

        def fail_first_tag_then_succeed(*args, **kwargs):
            tag = kwargs.get("json", {}).get("name")
            call_sequence.append(tag)
            if tag == md.TARGET_MODELS["tinyllama"][0]:
                raise requests.exceptions.ConnectionError("down")
            return MagicMock(status_code=200)

        with patch("model_downloader.requests.post", side_effect=fail_first_tag_then_succeed), \
             patch("model_downloader.check_storage_min_bytes", return_value=True), \
             patch("model_downloader.time.sleep"), \
             patch("model_downloader.notify") as mock_notify:
            result = md.download_model("tinyllama", min_free_gb=2)

        assert result is True
        assert md.TARGET_MODELS["tinyllama"][1] in call_sequence
        last_call_args = mock_notify.call_args_list[-1][0]
        assert any("Model Ready" in str(c) for c in last_call_args)

    def test_attempts_lru_eviction_when_storage_is_insufficient(self):
        with patch("model_downloader.check_storage_min_bytes", side_effect=[False, True]), \
             patch.object(md.cache, "least_recently_used", return_value="old:model"), \
             patch("model_downloader.delete_model", return_value=True) as mock_delete, \
             patch.object(md.cache, "forget_model") as mock_forget, \
             patch("model_downloader.requests.post") as mock_post:
            mock_post.return_value = MagicMock(status_code=200)
            result = md.download_model("tinyllama", min_free_gb=2)

        mock_delete.assert_called_once_with("old:model")
        mock_forget.assert_called_once_with("old:model")
        assert result is True

    def test_gives_up_when_cleanup_cannot_free_enough_space(self):
        with patch("model_downloader.check_storage_min_bytes", return_value=False), \
             patch.object(md.cache, "least_recently_used", return_value=None), \
             patch("model_downloader.notify") as mock_notify:
            result = md.download_model("tinyllama", min_free_gb=2)

        assert result is False
        first_call_args = mock_notify.call_args_list[0][0]
        assert any("Download Failed" in str(c) for c in first_call_args)


class TestIsOllamaRunning:
    def test_returns_true_when_reachable(self):
        with patch("model_downloader.requests.get") as mock_get:
            mock_get.return_value = MagicMock(status_code=200)
            assert md.is_ollama_running() is True

    def test_returns_false_when_unreachable(self):
        with patch("model_downloader.requests.get", side_effect=requests.exceptions.ConnectionError("down")):
            assert md.is_ollama_running() is False
