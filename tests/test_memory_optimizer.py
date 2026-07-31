import os
import subprocess
from unittest.mock import patch, MagicMock, mock_open

import pytest
import requests

import memory_optimizer as mo


@pytest.fixture
def optimizer():
    return mo.MemoryOptimizer(max_temp_celsius=65.0, min_free_storage_gb=1.5)


class TestThermalThrottle:
    def test_stays_at_four_threads_when_cool(self, optimizer):
        with patch.object(optimizer, "get_thermal_status", return_value=45.0):
            assert optimizer.enforce_thermal_throttle() is False
            assert os.environ["OLLAMA_CPU_THREADS"] == "4"

    def test_drops_to_two_threads_and_notifies_when_hot(self, optimizer):
        with patch.object(optimizer, "get_thermal_status", return_value=70.0), \
             patch("memory_optimizer.notify") as mock_notify, \
             patch("memory_optimizer.time.sleep"):
            assert optimizer.enforce_thermal_throttle() is True
            assert os.environ["OLLAMA_CPU_THREADS"] == "2"
            mock_notify.assert_called_once()

    def test_exactly_at_threshold_counts_as_hot(self, optimizer):
        with patch.object(optimizer, "get_thermal_status", return_value=65.0), \
             patch("memory_optimizer.notify"), patch("memory_optimizer.time.sleep"):
            assert optimizer.enforce_thermal_throttle() is True


class TestGetThermalStatus:
    def test_reads_millidegrees_and_converts_to_celsius(self, optimizer):
        with patch("memory_optimizer.os.path.exists",
                    side_effect=lambda p: p == "/sys/class/thermal/thermal_zone0/temp"), \
             patch("builtins.open", mock_open(read_data="45000")):
            assert optimizer.get_thermal_status() == 45.0

    def test_returns_zero_when_no_thermal_zone_is_readable(self, optimizer):
        with patch("memory_optimizer.os.path.exists", return_value=False):
            assert optimizer.get_thermal_status() == 0.0

    def test_falls_back_to_second_thermal_zone(self, optimizer):
        with patch("memory_optimizer.os.path.exists",
                    side_effect=lambda p: p == "/sys/class/thermal/thermal_zone1/temp"), \
             patch("builtins.open", mock_open(read_data="50000")):
            assert optimizer.get_thermal_status() == 50.0


class TestStorageCleanup:
    def test_no_eviction_when_storage_is_healthy(self, optimizer):
        with patch("memory_optimizer.shutil.disk_usage") as mock_disk, \
             patch.object(optimizer.cache, "least_recently_used") as mock_lru:
            mock_disk.return_value = MagicMock(free=10 * 1024**3)
            optimizer.check_storage_and_cleanup()
            mock_lru.assert_not_called()

    def test_evicts_lru_model_when_storage_is_low(self, optimizer):
        with patch("memory_optimizer.shutil.disk_usage") as mock_disk, \
             patch.object(optimizer.cache, "least_recently_used", return_value="old:model"), \
             patch.object(optimizer.cache, "forget_model") as mock_forget, \
             patch("memory_optimizer.requests.delete") as mock_delete:
            mock_disk.return_value = MagicMock(free=1 * 1024**3)
            mock_delete.return_value = MagicMock(status_code=200)
            optimizer.check_storage_and_cleanup()
            mock_delete.assert_called_once()
            mock_forget.assert_called_once_with("old:model")

    def test_no_op_when_storage_low_but_nothing_cached(self, optimizer):
        with patch("memory_optimizer.shutil.disk_usage") as mock_disk, \
             patch.object(optimizer.cache, "least_recently_used", return_value=None), \
             patch("memory_optimizer.requests.delete") as mock_delete:
            mock_disk.return_value = MagicMock(free=0.5 * 1024**3)
            optimizer.check_storage_and_cleanup()
            mock_delete.assert_not_called()

    def test_keeps_cache_entry_if_ollama_delete_fails(self, optimizer):
        with patch("memory_optimizer.shutil.disk_usage") as mock_disk, \
             patch.object(optimizer.cache, "least_recently_used", return_value="old:model"), \
             patch.object(optimizer.cache, "forget_model") as mock_forget, \
             patch("memory_optimizer.requests.delete") as mock_delete:
            mock_disk.return_value = MagicMock(free=1 * 1024**3)
            mock_delete.return_value = MagicMock(status_code=500)
            optimizer.check_storage_and_cleanup()
            mock_forget.assert_not_called()  # only forget on confirmed deletion


class TestOomRecovery:
    def test_sends_zero_keep_alive_when_ollama_is_reachable(self, optimizer):
        with patch("memory_optimizer.requests.post") as mock_post, \
             patch("memory_optimizer.notify"), \
             patch("memory_optimizer.subprocess.run") as mock_run:
            mock_post.return_value = MagicMock(status_code=200)
            optimizer.trigger_oom_recovery()
            mock_post.assert_called_once()
            mock_run.assert_not_called()

    def test_force_kills_when_ollama_is_unreachable(self, optimizer):
        with patch("memory_optimizer.requests.post",
                    side_effect=requests.exceptions.RequestException("down")), \
             patch("memory_optimizer.notify"), \
             patch("memory_optimizer.subprocess.run") as mock_run:
            optimizer.trigger_oom_recovery()
            mock_run.assert_called_once_with(["pkill", "-9", "-f", "ollama"], capture_output=True)


class TestSetupStorageSwap:
    def test_skips_setup_if_swap_file_already_exists(self, optimizer):
        with patch("memory_optimizer.os.path.exists", return_value=True), \
             patch("memory_optimizer.subprocess.run") as mock_run:
            assert optimizer.setup_storage_swap() is True
            mock_run.assert_not_called()

    def test_retries_on_transient_failure_then_succeeds(self, optimizer):
        call_count = {"n": 0}

        def flaky_run(cmd, **kwargs):
            call_count["n"] += 1
            if call_count["n"] == 1:
                raise subprocess.CalledProcessError(1, cmd)
            return MagicMock()

        with patch("memory_optimizer.os.path.exists", return_value=False), \
             patch("memory_optimizer.subprocess.run", side_effect=flaky_run), \
             patch("memory_optimizer.time.sleep"):
            assert optimizer.setup_storage_swap(max_retries=3) is True

    def test_gives_up_and_notifies_after_max_retries(self, optimizer):
        with patch("memory_optimizer.os.path.exists", return_value=False), \
             patch("memory_optimizer.subprocess.run",
                    side_effect=subprocess.CalledProcessError(1, "dd")), \
             patch("memory_optimizer.time.sleep"), \
             patch("memory_optimizer.notify") as mock_notify:
            assert optimizer.setup_storage_swap(max_retries=2) is False
            mock_notify.assert_called_once()
