import os


def _statvfs_info(path):
    try:
        st = os.statvfs(path)
        total = st.f_frsize * st.f_blocks
        free = st.f_frsize * st.f_bavail
        return total, free
    except OSError:
        return None, None


def detect_storage_volumes(storage_root="/storage"):
    """Scans Android's conventional external-storage mount points, visible
    to Termux once `termux-setup-storage` has been run. Internal storage is
    always /storage/emulated/0; anything else under /storage/<id>/ is a
    removable volume (MicroSD or USB OTG) Android has mounted."""
    volumes = []
    if not os.path.isdir(storage_root):
        return volumes

    for entry in sorted(os.listdir(storage_root)):
        path = os.path.join(storage_root, entry)
        if not os.path.isdir(path) or entry == "self":
            continue

        total, free = _statvfs_info(path)
        if total is None:
            continue  # unmounted or inaccessible

        volumes.append({
            "label": entry,
            "path": path,
            "is_internal": entry == "emulated",
            "total_bytes": total,
            "free_bytes": free,
            "fs_type": _filesystem_type(path),
        })
    return volumes


def _filesystem_type(path, mounts_file="/proc/mounts"):
    """Reads /proc/mounts to find the filesystem type backing `path` —
    matters because swapon only works on a handful of filesystems (ext4,
    f2fs...), never on vfat/exfat/fuse, which is what most removable
    Android storage is formatted/exposed as."""
    try:
        best_match = None
        with open(mounts_file, "r") as f:
            for line in f:
                parts = line.split()
                if len(parts) < 3:
                    continue
                mount_point, fs_type = parts[1], parts[2]
                if path == mount_point or path.startswith(mount_point.rstrip("/") + "/"):
                    if best_match is None or len(mount_point) > len(best_match[0]):
                        best_match = (mount_point, fs_type)
        return best_match[1] if best_match else "unknown"
    except OSError:
        return "unknown"


def recommend_swap_target(min_free_gb=4, storage_root="/storage"):
    """Picks the removable volume with the most free space that also meets
    the minimum size floor; returns None if nothing qualifies."""
    candidates = [
        v for v in detect_storage_volumes(storage_root)
        if not v["is_internal"] and v["free_bytes"] >= min_free_gb * 1024 ** 3
    ]
    if not candidates:
        return None
    return max(candidates, key=lambda v: v["free_bytes"])
