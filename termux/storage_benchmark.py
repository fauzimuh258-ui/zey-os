import os
import subprocess
import time


def benchmark_storage(directory, sample_mb=64, chunk_mb=1):
    """Sequential write+read throughput for `directory`. Uses a real file
    with an fsync so results reflect physical storage, not just page cache.
    The read figure is still a best-effort userspace measurement: without
    root (to drop caches) a same-process read shortly after write can be
    partly cushioned by page cache — treat it as an optimistic upper bound,
    not a guarantee of sustained real-world speed.
    """
    if not os.path.isdir(directory) or not os.access(directory, os.W_OK):
        return None

    test_path = os.path.join(directory, f".zeyos_bench_{int(time.time())}.tmp")
    chunk = bytes(i % 256 for i in range(chunk_mb * 1024 * 1024))
    chunks_needed = max(1, sample_mb // chunk_mb)

    try:
        write_start = time.monotonic()
        with open(test_path, "wb") as f:
            for _ in range(chunks_needed):
                f.write(chunk)
            f.flush()
            os.fsync(f.fileno())
        write_seconds = time.monotonic() - write_start

        try:
            subprocess.run(["sync"], capture_output=True, timeout=5)
        except (FileNotFoundError, subprocess.TimeoutExpired):
            pass

        read_start = time.monotonic()
        with open(test_path, "rb") as f:
            while f.read(chunk_mb * 1024 * 1024):
                pass
        read_seconds = time.monotonic() - read_start

        actual_mb = chunks_needed * chunk_mb
        return {
            "path": directory,
            "sample_mb": actual_mb,
            "write_mb_per_sec": round(actual_mb / write_seconds, 1) if write_seconds > 0 else None,
            "read_mb_per_sec": round(actual_mb / read_seconds, 1) if read_seconds > 0 else None,
        }
    finally:
        if os.path.exists(test_path):
            os.remove(test_path)


def estimate_seconds_per_token(shortfall_gb, read_mb_per_sec):
    """Lower-bound per-token latency once part of the model must be paged
    in from storage on every forward pass. Assumes the full shortfall is
    re-read each token — realistic for dense (non-MoE) models once weights
    exceed available RAM."""
    if not read_mb_per_sec or read_mb_per_sec <= 0:
        return None
    return (shortfall_gb * 1024) / read_mb_per_sec
