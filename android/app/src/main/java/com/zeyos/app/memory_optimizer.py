# Add this import at the top of memory_optimizer.py:
from swap_manager import create_swap_file, SwapCapabilityError

# Update MemoryOptimizer.__init__ to accept an optional external target,
# and setup_storage_swap() to try it first:

class MemoryOptimizer:
    def __init__(self, swap_size_mb=2048, max_temp_celsius=65.0, min_free_storage_gb=1.5,
                 external_swap_dir=None):
        self.swap_size_mb = swap_size_mb
        self.max_temp_celsius = max_temp_celsius
        self.min_free_storage_gb = min_free_storage_gb
        self.external_swap_dir = external_swap_dir
        self.swap_file = os.path.join(os.path.expanduser("~"), "zeyos_swap.img")
        self.cache = ModelCacheManager()

    def setup_storage_swap(self, max_retries=3):
        if self.external_swap_dir:
            try:
                size_gb = max(1, self.swap_size_mb // 1024)
                path = create_swap_file(self.external_swap_dir, size_gb)
                logger.info(f"External swap active at {path}")
                self.swap_file = path
                return True
            except SwapCapabilityError as e:
                logger.warning(f"External swap unavailable ({e}); falling back to internal storage.")
        # ... rest of the existing internal-storage dd/mkswap/swapon logic from Part 2 unchanged
