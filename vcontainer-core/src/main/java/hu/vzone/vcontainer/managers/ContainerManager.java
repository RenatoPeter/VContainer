package hu.vzone.vcontainer.managers;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.api.events.ContainerAddItemEvent;
import hu.vzone.vcontainer.api.events.ContainerWithdrawItemEvent;
import hu.vzone.vcontainer.storage.ContainerStorage;
import hu.vzone.vcontainer.storage.LocalContainerStorage;
import hu.vzone.vcontainer.storage.SqlContainerStorage;
import hu.vzone.vcontainer.storage.StorageSettings;
import hu.vzone.vcontainer.utils.ItemUtils;
import hu.vzone.vcontainer.utils.StoragePolicy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ContainerManager {
    private static final long AUTO_SAVE_TICKS = 20L * 60L * 2L;

    private final VContainer plugin;
    private final ContainerStorage storage;
    private final Map<UUID, List<ItemStack>> cache = new HashMap<>();
    private final Map<UUID, Long> dirtyVersions = new HashMap<>();
    private final Map<UUID, Long> containerVersions = new HashMap<>();
    private final Set<UUID> bulkOperationOwners = new HashSet<>();
    private final Object saveLock = new Object();
    private final CountDownLatch initialLoadFinished = new CountDownLatch(1);
    private long mutationVersion;
    private BukkitTask autoSaveTask;
    private BukkitTask initialLoadTask;
    private volatile boolean persistenceSuspended;
    private volatile boolean stackEnabled;
    private volatile int configuredMaxStack;
    private volatile boolean initialLoadComplete;
    private volatile boolean shuttingDown;
    private volatile boolean closeStorageAfterInitialLoad;
    private volatile Map<UUID, List<ItemStack>> pendingInitialLoad = Collections.emptyMap();
    private boolean storageClosed;

    public ContainerManager(VContainer plugin) {
        this.plugin = plugin;
        this.storage = createStorage(plugin);
        reloadRuntimeSettings();
        this.autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::flushDirtySync,
                AUTO_SAVE_TICKS,
                AUTO_SAVE_TICKS
        );
        startInitialLoad();
    }

    public int addItemToContainer(Player player, ItemStack item) {
        return addItemToContainer(player.getUniqueId(), player, item);
    }

    public int addItemToContainer(UUID ownerId, Player actor, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return 0;
        if (!initialLoadComplete) return 0;

        ContainerAddItemEvent event = new ContainerAddItemEvent(actor, item);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return 0;

        return addItemToContainer(ownerId, item);
    }

    public synchronized int addItemToContainer(UUID ownerId, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return 0;
        if (!initialLoadComplete) return 0;
        if (bulkOperationOwners.contains(ownerId)) return 0;

        List<ItemStack> list = getOrCreate(ownerId);
        StoragePolicy.Result policy = StoragePolicy.canAdd(plugin, list, item);
        if (!policy.allowed()) {
            plugin.getLogger().fine("Blocked container item add for " + ownerId + ": " + policy.reason());
            return 0;
        }

        int added = 0;

        if (stackEnabled) {
            int amountToAdd = item.getAmount();
            boolean targetHasMeta = item.hasItemMeta();
            org.bukkit.inventory.meta.ItemMeta targetMeta = targetHasMeta ? item.getItemMeta() : null;

            for (ItemStack current : list) {
                if (ItemUtils.isSameItemWithNBT(current, item, targetHasMeta, targetMeta)) {
                    int space = Math.min(configuredMaxStack, current.getMaxStackSize()) - current.getAmount();
                    if (space > 0) {
                        int add = Math.min(space, amountToAdd);
                        current.setAmount(current.getAmount() + add);
                        amountToAdd -= add;
                        added += add;
                    }
                    if (amountToAdd <= 0) break;
                }
            }

            while (amountToAdd > 0) {
                int split = Math.min(amountToAdd, Math.min(configuredMaxStack, item.getMaxStackSize()));
                ItemStack newStack = item.clone();
                newStack.setAmount(split);
                list.add(newStack);
                amountToAdd -= split;
                added += split;
            }
        } else {
            ItemStack clone = item.clone();
            list.add(clone);
            added = item.getAmount();
        }

        if (added > 0) markDirty(ownerId);
        return added;
    }

    public void removeItemFromContainer(Player player, ItemStack target) {
        removeItemFromContainer(player.getUniqueId(), target);
    }

    public void removeItemFromContainer(UUID ownerId, ItemStack target) {
        takeItemFromContainer(ownerId, target, target == null ? 0 : target.getAmount());
    }

    public int takeItemFromContainer(UUID ownerId, ItemStack target, int amount) {
        if (target == null || target.getType().isAir() || amount <= 0) return 0;
        if (!initialLoadComplete) return 0;
        synchronized (this) {
            if (bulkOperationOwners.contains(ownerId)) return 0;
        }

        ContainerWithdrawItemEvent event = new ContainerWithdrawItemEvent(ownerId, target, amount);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return 0;

        return takeItemFromContainerLocked(ownerId, target, amount);
    }

    private synchronized int takeItemFromContainerLocked(UUID ownerId, ItemStack target, int amount) {
        List<ItemStack> list = getOrCreate(ownerId);
        int remaining = amount;
        boolean targetHasMeta = target.hasItemMeta();
        org.bukkit.inventory.meta.ItemMeta targetMeta = targetHasMeta ? target.getItemMeta() : null;

        for (Iterator<ItemStack> it = list.iterator(); it.hasNext();) {
            ItemStack current = it.next();
            if (current == null) continue;
            if (ItemUtils.isSameItemWithNBT(current, target, targetHasMeta, targetMeta)) {
                int remove = Math.min(current.getAmount(), remaining);
                current.setAmount(current.getAmount() - remove);
                remaining -= remove;
                if (current.getAmount() <= 0) it.remove();
                if (remaining <= 0) break;
            }
        }

        int removed = amount - remaining;
        if (removed > 0) markDirty(ownerId);
        return removed;
    }

    public List<ItemStack> getAllItemFromContainer(Player player) {
        return getAllItemFromContainer(player.getUniqueId());
    }

    public synchronized List<ItemStack> getAllItemFromContainer(UUID ownerId) {
        if (!initialLoadComplete) return Collections.emptyList();
        return Collections.unmodifiableList(cloneItems(getOrCreate(ownerId)));
    }

    public synchronized List<ItemStack> getItemView(UUID ownerId) {
        if (!initialLoadComplete) return Collections.emptyList();
        return Collections.unmodifiableList(new ArrayList<>(getOrCreate(ownerId)));
    }

    public synchronized ItemStack peekFirstItem(UUID ownerId) {
        if (!initialLoadComplete) return null;
        for (ItemStack item : getOrCreate(ownerId)) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;
            return item.clone();
        }
        return null;
    }

    public synchronized Map<UUID, List<ItemStack>> snapshotContainers() {
        if (!initialLoadComplete) return Collections.emptyMap();
        return cloneContainers(cache);
    }

    public synchronized boolean itemInContainer(Player player, ItemStack item) {
        if (!initialLoadComplete) return false;
        List<ItemStack> list = getOrCreate(player.getUniqueId());
        return list.stream().anyMatch(s -> ItemUtils.isSameItemWithNBT(s, item));
    }

    public void clearContainer(Player player) {
        clearContainer(player.getUniqueId());
    }

    public synchronized void clearContainer(UUID ownerId) {
        if (!initialLoadComplete) return;
        if (bulkOperationOwners.contains(ownerId)) return;
        cache.put(ownerId, new ArrayList<>());
        markDirty(ownerId);
    }

    /**
     * Starts a bounded removal operation. Call {@link #processBatchTake(BatchTakeOperation, int)}
     * on the server thread until it completes, then commit or roll it back.
     */
    public synchronized BatchTakeOperation beginBatchTake(UUID ownerId, ItemStack target, int amount) {
        if (!initialLoadComplete || ownerId == null || target == null || target.getType().isAir() || amount <= 0 || bulkOperationOwners.contains(ownerId)) {
            return null;
        }

        ContainerWithdrawItemEvent event = new ContainerWithdrawItemEvent(ownerId, target, amount);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return null;

        ItemStack comparisonTarget = target.clone();
        comparisonTarget.setAmount(1);
        bulkOperationOwners.add(ownerId);
        return new BatchTakeOperation(
                ownerId,
                comparisonTarget,
                amount,
                comparisonTarget.hasItemMeta(),
                comparisonTarget.hasItemMeta() ? comparisonTarget.getItemMeta() : null
        );
    }

    /**
     * Removes at most {@code maxStacks} storage stacks. This keeps bulk sales below a predictable
     * per-tick work limit while the owner is locked against conflicting mutations.
     */
    public synchronized BatchTakeProgress processBatchTake(BatchTakeOperation operation, int maxStacks) {
        if (!isActiveBatchOperation(operation) || maxStacks <= 0) {
            return BatchTakeProgress.invalid();
        }

        List<ItemStack> list = getOrCreate(operation.ownerId);
        int scanned = 0;
        while (operation.cursor < list.size() && operation.remaining > 0 && scanned < maxStacks) {
            ItemStack current = list.get(operation.cursor);
            scanned++;
            if (!ItemUtils.isSameItemWithNBT(current, operation.target, operation.targetHasMeta, operation.targetMeta)) {
                operation.cursor++;
                continue;
            }

            int removed = Math.min(current.getAmount(), operation.remaining);
            operation.remaining -= removed;
            operation.removed += removed;
            if (removed == current.getAmount()) {
                list.remove(operation.cursor);
                operation.removedStacks.add(current);
            } else {
                ItemStack removedStack = current.clone();
                removedStack.setAmount(removed);
                operation.removedStacks.add(removedStack);
                current.setAmount(current.getAmount() - removed);
                operation.cursor++;
            }
        }

        if (operation.remaining <= 0 || operation.cursor >= list.size()) {
            operation.complete = true;
        }
        return new BatchTakeProgress(true, operation.complete, operation.removed, operation.remaining, scanned);
    }

    /** Commits an entirely completed operation and makes it eligible for persistence. */
    public synchronized boolean commitBatchTake(BatchTakeOperation operation) {
        if (!isActiveBatchOperation(operation) || !operation.complete || operation.remaining != 0) {
            return false;
        }

        markDirty(operation.ownerId);
        finishBatchOperation(operation);
        operation.removedStacks.clear();
        return true;
    }

    /** Restores all removed stacks without invoking storage policies or NBT comparisons. */
    public synchronized void rollbackBatchTake(BatchTakeOperation operation) {
        if (!isActiveBatchOperation(operation)) return;

        getOrCreate(operation.ownerId).addAll(operation.removedStacks);
        operation.removedStacks.clear();
        finishBatchOperation(operation);
    }

    public synchronized boolean isBulkOperationActive(UUID ownerId) {
        return bulkOperationOwners.contains(ownerId);
    }

    public void clearCacheFor(UUID playerId) {
        // Containers are loaded once and kept in memory so delayed saves cannot lose dirty data.
    }

    public void flushAllSync() {
        if (autoSaveTask != null) autoSaveTask.cancel();
        shuttingDown = true;
        if (!initialLoadComplete && !awaitInitialLoad()) {
            closeStorageAfterInitialLoad = true;
            if (initialLoadFinished.getCount() == 0L) {
                closeStorage();
            }
            plugin.getLogger().warning("Container startup load is still running; storage will close after it finishes.");
            return;
        }
        completeInitialLoad();
        if (!persistenceSuspended) flushSync();
        closeStorage();
    }

    public void flushSync() {
        if (persistenceSuspended || !initialLoadComplete) return;
        synchronized (saveLock) {
            flushDirtySync();
        }
    }

    public void setPersistenceSuspended(boolean suspended) {
        persistenceSuspended = suspended;
    }

    public boolean isPersistenceSuspended() {
        return persistenceSuspended;
    }

    public synchronized int dirtyCount() {
        return dirtyVersions.size();
    }

    public synchronized long getContainerVersion(UUID ownerId) {
        return containerVersions.getOrDefault(ownerId, 0L);
    }

    public void reloadRuntimeSettings() {
        stackEnabled = plugin.getConfig().getBoolean("stack", true);
        configuredMaxStack = Math.max(1, plugin.getConfig().getInt("max-stack", 64));
    }

    public boolean isInitialLoadComplete() {
        return initialLoadComplete;
    }

    private void startInitialLoad() {
        initialLoadTask = Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, List<ItemStack>> loaded = Collections.emptyMap();
            try {
                loaded = storage.loadAll();
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Failed to load container data during startup.", ex);
            } finally {
                pendingInitialLoad = loaded == null ? Collections.emptyMap() : loaded;
                initialLoadFinished.countDown();
            }

            if (shuttingDown) {
                if (closeStorageAfterInitialLoad) {
                    closeStorage();
                }
                return;
            }
            Bukkit.getScheduler().runTask(plugin, this::completeInitialLoad);
        });
    }

    private synchronized void completeInitialLoad() {
        if (initialLoadComplete || initialLoadFinished.getCount() != 0L) return;

        cache.clear();
        // The load task owns this map until now, so publishing it avoids recloning every stack on the server thread.
        cache.putAll(pendingInitialLoad);
        pendingInitialLoad = Collections.emptyMap();
        initialLoadComplete = true;
        plugin.getLogger().info("Loaded " + cache.size() + " cached container(s) from " + StorageSettings.from(plugin).type() + " storage.");
    }

    private boolean awaitInitialLoad() {
        try {
            return initialLoadFinished.await(10L, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private synchronized void closeStorage() {
        if (storageClosed) return;
        storageClosed = true;
        storage.close();
    }

    private synchronized List<ItemStack> getOrCreate(UUID id) {
        return cache.computeIfAbsent(id, ignored -> new ArrayList<>());
    }

    private synchronized void markDirty(UUID id) {
        long version = ++mutationVersion;
        dirtyVersions.put(id, version);
        containerVersions.put(id, version);
    }

    private boolean isActiveBatchOperation(BatchTakeOperation operation) {
        return operation != null && operation.active && bulkOperationOwners.contains(operation.ownerId);
    }

    private void finishBatchOperation(BatchTakeOperation operation) {
        bulkOperationOwners.remove(operation.ownerId);
        operation.active = false;
    }

    private void flushDirtySync() {
        synchronized (saveLock) {
            Map<UUID, DirtyContainerSnapshot> snapshots = dirtySnapshots();
            for (Map.Entry<UUID, DirtyContainerSnapshot> entry : snapshots.entrySet()) {
                if (storage.save(entry.getKey(), entry.getValue().items())) {
                    markSaved(entry.getKey(), entry.getValue().version());
                }
            }
        }
    }

    private synchronized Map<UUID, DirtyContainerSnapshot> dirtySnapshots() {
        Map<UUID, DirtyContainerSnapshot> snapshots = new HashMap<>();
        for (Map.Entry<UUID, Long> entry : dirtyVersions.entrySet()) {
            snapshots.put(entry.getKey(), new DirtyContainerSnapshot(
                    cloneItems(cache.getOrDefault(entry.getKey(), new ArrayList<>())),
                    entry.getValue()
            ));
        }
        return snapshots;
    }

    private synchronized void markSaved(UUID ownerId, long savedVersion) {
        Long currentVersion = dirtyVersions.get(ownerId);
        if (currentVersion != null && currentVersion == savedVersion) {
            dirtyVersions.remove(ownerId);
        }
    }

    private ContainerStorage createStorage(VContainer plugin) {
        StorageSettings settings = StorageSettings.from(plugin);
        if (settings.type() == StorageSettings.StorageType.LOCAL) {
            return new LocalContainerStorage(plugin);
        }
        return new SqlContainerStorage(plugin, settings);
    }

    private Map<UUID, List<ItemStack>> cloneContainers(Map<UUID, List<ItemStack>> source) {
        Map<UUID, List<ItemStack>> copy = new HashMap<>();
        for (Map.Entry<UUID, List<ItemStack>> entry : source.entrySet()) {
            copy.put(entry.getKey(), cloneItems(entry.getValue()));
        }
        return copy;
    }

    private List<ItemStack> cloneItems(List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                copy.add(item.clone());
            }
        }
        return copy;
    }

    private record DirtyContainerSnapshot(List<ItemStack> items, long version) {
    }

    public static final class BatchTakeOperation {
        private final UUID ownerId;
        private final ItemStack target;
        private final int requested;
        private final boolean targetHasMeta;
        private final org.bukkit.inventory.meta.ItemMeta targetMeta;
        private final List<ItemStack> removedStacks = new ArrayList<>();
        private int cursor;
        private int remaining;
        private int removed;
        private boolean complete;
        private boolean active = true;

        private BatchTakeOperation(UUID ownerId, ItemStack target, int requested, boolean targetHasMeta, org.bukkit.inventory.meta.ItemMeta targetMeta) {
            this.ownerId = ownerId;
            this.target = target;
            this.requested = requested;
            this.targetHasMeta = targetHasMeta;
            this.targetMeta = targetMeta;
            this.remaining = requested;
        }

        public int requested() {
            return requested;
        }
    }

    public record BatchTakeProgress(boolean active, boolean complete, int removed, int remaining, int scannedStacks) {
        private static BatchTakeProgress invalid() {
            return new BatchTakeProgress(false, true, 0, 0, 0);
        }
    }
}
