import math
import os
import shutil
import subprocess

from storage_detector import _filesystem_type

SWAPPABLE_FILESYSTEMS = {"ext4", "ext3", "ext2", "f2fs", "btrfs", "xfs"}


class SwapCapabilityError(Exception):
    """Raised when the target path can't support swapon, with a reason a
    non-root user can actually act on."""


def check_swap_capability(target_dir):
    """Runs the checks that determine whether swapon on this path has any
    chance of working, before spending time writing a multi-GB file."""
    reasons = []

    if os.geteuid() != 0:
        reasons.append(
            "Not running as root. swapon()/swapoff() require CAP_SYS_ADMIN on Android — "
            "this will almost certainly fail with 'Operation not permitted' without root."
        )

    fs_type = _filesystem_type(target_dir)
    if fs_type not in SWAPPABLE_FILESYSTEMS:
        reasons.append(
            f"{target_dir} is mounted as '{fs_type}'. swapon needs a real block-backed "
            f"filesystem (ext4/f2fs/...) — vfat/exfat/fuse (the usual format for MicroSD "
            f"and USB-OTG mass storage on Android) cannot host a swap file, no matter how "
            f"the file itself is created."
        )

    return reasons  # empty list == no known blockers


def dynamic_swap_size_gb(model_size_gb, available_ram_gb, safety_factor=1.3, min_gb=1, max_gb=16):
    """Sizes swap to cover the shortfall between model size and available
    RAM, with headroom for KV-cache/context growth — not the whole model,
    just what doesn't already fit."""
    shortfall = max(model_size_gb - available_ram_gb, 0.0)
    sized = shortfall * safety_factor
    return max(min_gb, min(max_gb, math.ceil(sized)))


def create_swap_file(target_dir, size_gb, force=False):
    """Creates and activates a swap file at `target_dir`. Raises
    SwapCapabilityError up front instead of failing opaquely mid-write if
    check_swap_capability() found a blocker — pass force=True to attempt it
    anyway if you know your setup better than the heuristic (e.g. an
    ext4-formatted, non-FUSE-mounted USB drive on a rooted device)."""
    blockers = check_swap_capability(target_dir)
    if blockers and not force:
        raise SwapCapabilityError(" ".join(blockers))

    free_bytes = shutil.disk_usage(target_dir).free
    required_bytes = size_gb * 1024 ** 3
    if free_bytes < required_bytes:
        raise SwapCapabilityError(
            f"Need {size_gb}GB but only {free_bytes / 1024**3:.1f}GB free at {target_dir}."
        )

    swap_path = os.path.join(target_dir, "zeyos_external_swap.img")
    if os.path.exists(swap_path):
        return swap_path  # already provisioned

    subprocess.run(
        ["dd", "if=/dev/zero", f"of={swap_path}", "bs=1M", f"count={size_gb * 1024}"],
        check=True, capture_output=True,
    )
    os.chmod(swap_path, 0o600)
    subprocess.run(["mkswap", swap_path], check=True, capture_output=True)
    subprocess.run(["swapon", swap_path], check=True, capture_output=True)
    return swap_path


def remove_swap_file(swap_path):
    subprocess.run(["swapoff", swap_path], capture_output=True)
    if os.path.exists(swap_path):
        os.remove(swap_path)
