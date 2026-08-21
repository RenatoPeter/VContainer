package hu.vzone.vcontainer.managers;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.gui.ContainerGUI;
import hu.vzone.vcontainer.api.events.ContainerAddItemEvent;
import hu.vzone.vcontainer.api.events.ContainerWithdrawItemEvent;
import hu.vzone.vcontainer.storage.ContainerStorage;
import hu.vzone.vcontainer.storage.LocalContainerStorage;
import hu.vzone.vcontainer.storage.SqlContainerStorage;
import hu.vzone.vcontainer.storage.PlayerContainerLoadResult;
import hu.vzone.vcontainer.storage.StorageSettings;
import hu.vzone.vcontainer.utils.ItemUtils;
import hu.vzone.vcontainer.utils.ContainerDebugLogger;
import hu.vzone.vcontainer.utils.StoragePolicy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class ContainerManager {
    private static final long AUTO_SAVE_TICKS = 20L * 60L * 2L;
    private static final int UNLIMITED_STACK_SIZE = Integer.MAX_VALUE;

    private final VContainer plugin;
    private final ContainerStorage storage;
    private final Map<UUID, List<ItemStack>> cache = new HashMap<>();
    private final Map<UUID, ContainerState> containerStates = new HashMap<>();
    private final Map<UUID, Long> sessions = new HashMap<>();
    private final Map<UUID, Long> dirtyVersions = new HashMap<>();
    private final Map<UUID, Long> inFlightVersions = new HashMap<>();
    private final Map<UUID, Long> containerVersions = new HashMap<>();
    private final Set<UUID> bulkOperationOwners = new HashSet<>();
    private final Object flushStateLock = new Object();
    private final ExecutorService persistenceExecutor;
    private final hu.vzone.vcontainer.storage.ContainerRecoveryJournal recoveryJournal;
    private long mutationVersion;
    private long sessionCounter;
    private boolean flushInProgress;
    private BukkitTask autoSaveTask;
    private volatile boolean persistenceSuspended;
    private volatile boolean stackEnabled;
    private volatile int configuredMaxStack;
    private volatile boolean running = true;
    private boolean storageClosed;

    public ContainerManager(VContainer plugin) {
        this.plugin = plugin;
        this.storage = createStorage(plugin);
        this.persistenceExecutor = Executors.newFixedThreadPool(2, persistenceThreadFactory());
        this.recoveryJournal = new hu.vzone.vcontainer.storage.ContainerRecoveryJournal(plugin);
        reloadRuntimeSettings();
        this.autoSaveTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::startAutosave,
                AUTO_SAVE_TICKS,
                AUTO_SAVE_TICKS
        );
    }

    public int addItemToContainer(Player player, ItemStack item) {
        return addItemToContainer(player.getUniqueId(), player, item);
    }

    public int addItemToContainer(UUID ownerId, Player actor, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return 0;
        if (!canMutate(ownerId)) return 0;

        ContainerAddItemEvent event = new ContainerAddItemEvent(actor, item);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return 0;

        return addItemToContainer(ownerId, item);
    }

    public synchronized int addItemToContainer(UUID ownerId, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return 0;
        if (!canMutate(ownerId)) return 0;
        if (bulkOperationOwners.contains(ownerId)) return 0;

        List<ItemStack> list = readyItems(ownerId);
        if (list == null) return 0;
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
            ItemStack primaryStack = null;

            for (ItemStack current : list) {
                if (ItemUtils.isSameItemWithNBT(current, item, targetHasMeta, targetMeta)) {
                    if (primaryStack == null) {
                        primaryStack = current;
                    }
                }
            }

            if (primaryStack != null) {
                int space = configuredMaxStack - primaryStack.getAmount();
                if (space > 0) {
                        int add = Math.min(space, amountToAdd);
                        primaryStack.setAmount(primaryStack.getAmount() + add);
                        amountToAdd -= add;
                        added += add;
                }
            }

            while (amountToAdd > 0) {
                int split = Math.min(amountToAdd, configuredMaxStack);
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
        return takeItemFromContainer(ownerId, target, amount, -1L, false);
    }

    /**
     * Atomically removes the requested amount from the in-memory container only when all of it is
     * available. It rebuilds the backing ArrayList once instead of shifting it for every removed stack.
     */
    public int takeExactItemFromContainer(UUID ownerId, ItemStack target, int amount) {
        if (target == null || target.getType().isAir() || amount <= 0 || !canMutate(ownerId)) return 0;
        synchronized (this) {
            if (bulkOperationOwners.contains(ownerId)) return 0;
        }

        ContainerWithdrawItemEvent event = new ContainerWithdrawItemEvent(ownerId, target, amount);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return 0;

        return takeExactItemFromContainerLocked(ownerId, target, amount);
    }

    private synchronized int takeExactItemFromContainerLocked(UUID ownerId, ItemStack target, int amount) {
        List<ItemStack> list = readyItems(ownerId);
        if (list == null) return 0;
        boolean targetHasMeta = target.hasItemMeta();
        org.bukkit.inventory.meta.ItemMeta targetMeta = targetHasMeta ? target.getItemMeta() : null;

        long available = 0L;
        for (ItemStack current : list) {
            if (current == null || !ItemUtils.isSameItemWithNBT(current, target, targetHasMeta, targetMeta)) continue;
            available += current.getAmount();
            if (available >= amount) break;
        }
        if (available < amount) return 0;

        int remaining = amount;
        List<ItemStack> retained = new ArrayList<>(list.size());
        for (ItemStack current : list) {
            if (current == null || remaining <= 0
                    || !ItemUtils.isSameItemWithNBT(current, target, targetHasMeta, targetMeta)) {
                retained.add(current);
                continue;
            }

            int remove = Math.min(current.getAmount(), remaining);
            remaining -= remove;
            int stackRemaining = current.getAmount() - remove;
            if (stackRemaining > 0) {
                current.setAmount(stackRemaining);
                retained.add(current);
            }
        }

        list.clear();
        list.addAll(retained);
        markDirty(ownerId);
        return amount;
    }

    /**
     * Removes an item only when the caller is still looking at the supplied container version.
     * This protects the inventory GUI from stale click packets after another viewer changed it.
     */
    public int takeItemFromContainerAtVersion(UUID ownerId, ItemStack target, int amount, long expectedVersion) {
        return takeItemFromContainer(ownerId, target, amount, expectedVersion, true);
    }

    private int takeItemFromContainer(UUID ownerId, ItemStack target, int amount, long expectedVersion, boolean requireExactItem) {
        if (target == null || target.getType().isAir() || amount <= 0) return 0;
        if (!canMutate(ownerId)) return 0;
        synchronized (this) {
            if (bulkOperationOwners.contains(ownerId)) return 0;
        }

        ContainerWithdrawItemEvent event = new ContainerWithdrawItemEvent(ownerId, target, amount);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return 0;

        return takeItemFromContainerLocked(ownerId, target, amount, expectedVersion, requireExactItem);
    }

    private synchronized int takeItemFromContainerLocked(UUID ownerId, ItemStack target, int amount, long expectedVersion, boolean requireExactItem) {
        if (expectedVersion >= 0L && containerVersions.getOrDefault(ownerId, 0L) != expectedVersion) {
            return 0;
        }
        List<ItemStack> list = readyItems(ownerId);
        if (list == null) return 0;
        int remaining = amount;
        boolean targetHasMeta = target.hasItemMeta();
        org.bukkit.inventory.meta.ItemMeta targetMeta = targetHasMeta ? target.getItemMeta() : null;

        for (Iterator<ItemStack> it = list.iterator(); it.hasNext();) {
            ItemStack current = it.next();
            if (current == null) continue;
            boolean matches = requireExactItem
                    ? ItemUtils.isSameStoredItem(current, target, targetHasMeta, targetMeta)
                    : ItemUtils.isSameItemWithNBT(current, target, targetHasMeta, targetMeta);
            if (matches) {
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

    /** Restores a withdrawal that could not be delivered to a player's inventory. */
    public synchronized void restoreItemToContainer(UUID ownerId, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0 || !canMutate(ownerId)) return;
        List<ItemStack> list = readyItems(ownerId);
        if (list == null) return;
        list.add(item.clone());
        markDirty(ownerId);
    }

    public List<ItemStack> getAllItemFromContainer(Player player) {
        return getAllItemFromContainer(player.getUniqueId());
    }

    public synchronized List<ItemStack> getAllItemFromContainer(UUID ownerId) {
        List<ItemStack> items = readyItems(ownerId);
        return items == null ? Collections.emptyList() : Collections.unmodifiableList(cloneItems(items));
    }

    public synchronized List<ItemStack> getItemView(UUID ownerId) {
        List<ItemStack> items = readyItems(ownerId);
        return items == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(items));
    }

    public synchronized ItemStack peekFirstItem(UUID ownerId) {
        List<ItemStack> items = readyItems(ownerId);
        if (items == null) return null;
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;
            return item.clone();
        }
        return null;
    }

    public synchronized Map<UUID, List<ItemStack>> snapshotContainers() {
        return cloneContainers(cache);
    }

    public synchronized boolean itemInContainer(Player player, ItemStack item) {
        List<ItemStack> list = readyItems(player.getUniqueId());
        if (list == null) return false;
        return list.stream().anyMatch(s -> ItemUtils.isSameItemWithNBT(s, item));
    }

    public void clearContainer(Player player) {
        clearContainer(player.getUniqueId());
    }

    public synchronized void clearContainer(UUID ownerId) {
        if (!canMutate(ownerId)) return;
        if (bulkOperationOwners.contains(ownerId)) return;
        cache.put(ownerId, new ArrayList<>());
        markDirty(ownerId);
    }

    /**
     * Starts a bounded removal operation. Call {@link #processBatchTake(BatchTakeOperation, int)}
     * on the server thread until it completes, then commit or roll it back.
     */
    public synchronized BatchTakeOperation beginBatchTake(UUID ownerId, ItemStack target, int amount) {
        if (!canMutate(ownerId) || ownerId == null || target == null || target.getType().isAir() || amount <= 0 || bulkOperationOwners.contains(ownerId)) {
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
     * Plans removal from at most {@code maxStacks} storage stacks. The list itself remains untouched
     * until commit, avoiding repeated ArrayList shifts for large legacy containers.
     */
    public synchronized BatchTakeProgress processBatchTake(BatchTakeOperation operation, int maxStacks) {
        if (!isActiveBatchOperation(operation) || maxStacks <= 0) {
            return BatchTakeProgress.invalid();
        }

        List<ItemStack> list = readyItems(operation.ownerId);
        if (list == null) return BatchTakeProgress.invalid();
        int scanned = 0;
        while (operation.cursor < list.size() && operation.remaining > 0 && scanned < maxStacks) {
            ItemStack current = list.get(operation.cursor);
            scanned++;
            if (current == null || !ItemUtils.isSameItemWithNBT(current, operation.target, operation.targetHasMeta, operation.targetMeta)) {
                operation.cursor++;
                continue;
            }

            int removed = Math.min(current.getAmount(), operation.remaining);
            operation.remaining -= removed;
            operation.removed += removed;
            operation.removals.merge(current, removed, Integer::sum);
            operation.cursor++;
        }

        if (operation.remaining <= 0 || operation.cursor >= list.size()) {
            operation.complete = true;
        }
        return new BatchTakeProgress(true, operation.complete, operation.removed, operation.remaining, scanned);
    }

    /** Commits an entirely completed operation and makes it eligible for persistence. */
    public synchronized boolean commitBatchTake(BatchTakeOperation operation) {
        if (operation == null || !canMutate(operation.ownerId) || !isActiveBatchOperation(operation) || !operation.complete || operation.remaining != 0) {
            return false;
        }

        List<ItemStack> list = readyItems(operation.ownerId);
        if (list == null) return false;
        List<ItemStack> retained = new ArrayList<>(list.size());
        for (ItemStack current : list) {
            int remove = operation.removals.getOrDefault(current, 0);
            if (remove <= 0) {
                retained.add(current);
                continue;
            }

            int remaining = current.getAmount() - remove;
            if (remaining > 0) {
                current.setAmount(remaining);
                retained.add(current);
            }
        }
        // Replacing the contents once is O(n); iterator removal here would be O(n^2).
        list.clear();
        list.addAll(retained);
        markDirty(operation.ownerId);
        finishBatchOperation(operation);
        operation.removals.clear();
        return true;
    }

    /** The storage list is unchanged before commit, so rollback only releases the owner lock. */
    public synchronized void rollbackBatchTake(BatchTakeOperation operation) {
        if (!isActiveBatchOperation(operation)) return;

        operation.removals.clear();
        finishBatchOperation(operation);
    }

    public synchronized boolean isBulkOperationActive(UUID ownerId) {
        return bulkOperationOwners.contains(ownerId);
    }

    public void clearCacheFor(UUID playerId) {
        unloadPlayer(playerId);
    }

    /** Starts the owner-scoped asynchronous load for a joining player. */
    public void loadPlayer(Player player) {
        if (player == null || !player.isOnline() || !running) return;
        UUID ownerId = player.getUniqueId();
        long session;
        synchronized (this) {
            ContainerState current = containerStates.get(ownerId);
            if (current == ContainerState.READY || current == ContainerState.LOADING) return;
            if (current == ContainerState.UNLOADING) {
                containerStates.put(ownerId, ContainerState.READY);
                sessions.put(ownerId, nextSession(ownerId));
                return;
            }
            containerStates.put(ownerId, ContainerState.LOADING);
            session = nextSession(ownerId);
        }

        persistenceExecutor.execute(() -> loadPlayerAsync(ownerId, session));
    }

    /** Loads already-online players after a runtime plugin reload without scanning the whole database. */
    public void loadOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) loadPlayer(player);
    }

    /** Final player save is asynchronous; an unsaved failure remains in UNLOADING cache for retry/rejoin. */
    public void unloadPlayer(UUID ownerId) {
        if (ownerId == null) return;
        DirtyContainerSnapshot snapshot = null;
        long session;
        synchronized (this) {
            ContainerState state = containerStates.get(ownerId);
            if (state == null) return;
            if (state == ContainerState.LOADING) {
                containerStates.remove(ownerId);
                // Removing the generation invalidates the in-flight load without retaining an offline UUID.
                sessions.remove(ownerId);
                return;
            }
            if (state != ContainerState.READY) return;
            containerStates.put(ownerId, ContainerState.UNLOADING);
            session = nextSession(ownerId);
            Long version = dirtyVersions.get(ownerId);
            if (version == null) {
                evict(ownerId);
                return;
            }
            if (version.equals(inFlightVersions.get(ownerId))) return;
            snapshot = new DirtyContainerSnapshot(cloneItems(cache.getOrDefault(ownerId, Collections.emptyList())), version);
        }
        submitSingleSave(ownerId, session, snapshot);
    }

    public void flushAllSync() {
        if (autoSaveTask != null) autoSaveTask.cancel();
        running = false;
        DirtyCacheSnapshot snapshot = dirtyCacheSnapshot();
        for (Map.Entry<UUID, DirtyContainerSnapshot> entry : snapshot.containers().entrySet()) {
            try {
                recoveryJournal.write(entry.getKey(), entry.getValue().items());
            } catch (java.io.IOException exception) {
                plugin.getLogger().severe("Failed to write container recovery journal for " + entry.getKey() + ": " + exception.getMessage());
            }
        }
        persistenceExecutor.shutdownNow();
        closeStorage();
    }

    /** Runtime unload is journal-backed and never performs network I/O on the server thread. */
    public void shutdownWithoutBlockingFlush() {
        flushAllSync();
    }

    public void flushSync() {
        flushSync(ContainerDebugLogger.SaveCause.MANUAL);
    }

    private void flushSync(ContainerDebugLogger.SaveCause cause) {
        if (persistenceSuspended) return;
        startAutosave();
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
        int configured = plugin.getConfig().getInt("max-stack", -1);
        configuredMaxStack = configured <= 0 ? UNLIMITED_STACK_SIZE : Math.max(1, configured);
    }

    /** True when stored stack amounts can exceed normal Minecraft inventory stack sizes. */
    public boolean usesUnlimitedStacks() {
        return stackEnabled && configuredMaxStack == UNLIMITED_STACK_SIZE;
    }

    private synchronized void closeStorage() {
        if (storageClosed) return;
        storageClosed = true;
        storage.close();
    }

    private boolean beginFlush(boolean allowShutdown) {
        synchronized (flushStateLock) {
            if (flushInProgress || storageClosed) return false;
            if (!running) return false;
            flushInProgress = true;
            return true;
        }
    }

    private void endFlush() {
        synchronized (flushStateLock) {
            flushInProgress = false;
            flushStateLock.notifyAll();
        }
    }

    private synchronized void markDirty(UUID id) {
        long version = ++mutationVersion;
        dirtyVersions.put(id, version);
        containerVersions.put(id, version);
        ContainerDebugLogger.containerAction(id);
        queueOpenViewRefresh(id);
    }

    /** All mutation sources (API, minions, hoppers and players) share this one live-view notification. */
    private void queueOpenViewRefresh(UUID ownerId) {
        if (Bukkit.isPrimaryThread()) {
            ContainerGUI.queueRefresh(ownerId);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> ContainerGUI.queueRefresh(ownerId));
    }

    private boolean isActiveBatchOperation(BatchTakeOperation operation) {
        return operation != null && operation.active && bulkOperationOwners.contains(operation.ownerId);
    }

    private void finishBatchOperation(BatchTakeOperation operation) {
        bulkOperationOwners.remove(operation.ownerId);
        operation.active = false;
    }

    /** Builds a detached snapshot on the server thread, then performs JDBC on the bounded persistence executor. */
    private void startAutosave() {
        if (persistenceSuspended || !beginFlush(false)) return;
        DirtyCacheSnapshot snapshot = dirtyCacheSnapshot();
        if (snapshot.containers().isEmpty()) {
            endFlush();
            return;
        }
        plugin.getLogger().info("Autosave started: " + snapshot.containers().size() + " dirty container(s).");
        markInFlight(snapshot);
        try {
            persistenceExecutor.execute(() -> {
                try {
                    persistSnapshot(snapshot, ContainerDebugLogger.SaveCause.AUTO_SAVE);
                } finally {
                    clearInFlight(snapshot);
                    endFlush();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            clearInFlight(snapshot);
            endFlush();
        }
    }

    private boolean persistSnapshot(DirtyCacheSnapshot snapshot, ContainerDebugLogger.SaveCause cause) {
        if (storage.saveAll(snapshot.itemLists())) {
            for (Map.Entry<UUID, DirtyContainerSnapshot> entry : snapshot.containers().entrySet()) {
                markSaved(entry.getKey(), entry.getValue().version());
                deleteRecoveryJournal(entry.getKey());
                ContainerDebugLogger.containerSaved(entry.getKey(), entry.getValue().items(), cause);
            }
            if (cause == ContainerDebugLogger.SaveCause.AUTO_SAVE) {
                plugin.getLogger().info("Autosave completed: " + snapshot.containers().size()
                        + " container(s), remaining dirty: " + dirtyCount() + ".");
            } else if (cause == ContainerDebugLogger.SaveCause.SHUTDOWN) {
                plugin.getLogger().info("Shutdown flush completed: " + snapshot.containers().size() + " container(s).");
            }
            return true;
        }
        for (UUID ownerId : snapshot.containers().keySet()) {
            ContainerDebugLogger.containerSaveFailed(ownerId, cause);
        }
        plugin.getLogger().warning((cause == ContainerDebugLogger.SaveCause.SHUTDOWN ? "Shutdown flush" : "Autosave")
                + " failed: " + snapshot.containers().size() + " container(s) remain dirty.");
        return false;
    }

    /** Captures only changed containers before one atomic persistence transaction. */
    private synchronized DirtyCacheSnapshot dirtyCacheSnapshot() {
        Map<UUID, DirtyContainerSnapshot> snapshots = new HashMap<>();
        for (Map.Entry<UUID, Long> entry : dirtyVersions.entrySet()) {
            snapshots.put(entry.getKey(), new DirtyContainerSnapshot(
                    cloneItems(cache.getOrDefault(entry.getKey(), Collections.emptyList())),
                    entry.getValue()
            ));
        }
        return new DirtyCacheSnapshot(snapshots);
    }

    private synchronized void markSaved(UUID ownerId, long savedVersion) {
        Long currentVersion = dirtyVersions.get(ownerId);
        if (currentVersion != null && currentVersion == savedVersion) {
            dirtyVersions.remove(ownerId);
            if (containerStates.get(ownerId) == ContainerState.UNLOADING) evict(ownerId);
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

    private record DirtyCacheSnapshot(Map<UUID, DirtyContainerSnapshot> containers) {
        private Map<UUID, List<ItemStack>> itemLists() {
            Map<UUID, List<ItemStack>> result = new HashMap<>();
            for (Map.Entry<UUID, DirtyContainerSnapshot> entry : containers.entrySet()) {
                result.put(entry.getKey(), entry.getValue().items());
            }
            return result;
        }
    }

    private synchronized void markInFlight(DirtyCacheSnapshot snapshot) {
        for (Map.Entry<UUID, DirtyContainerSnapshot> entry : snapshot.containers().entrySet()) {
            inFlightVersions.put(entry.getKey(), entry.getValue().version());
        }
    }

    private synchronized void clearInFlight(DirtyCacheSnapshot snapshot) {
        for (Map.Entry<UUID, DirtyContainerSnapshot> entry : snapshot.containers().entrySet()) {
            if (entry.getValue().version() == inFlightVersions.getOrDefault(entry.getKey(), -1L)) {
                inFlightVersions.remove(entry.getKey());
            }
        }
    }

    private synchronized boolean canMutate(UUID ownerId) {
        return running && ownerId != null && containerStates.get(ownerId) == ContainerState.READY
                && Bukkit.getPlayer(ownerId) != null;
    }

    private synchronized List<ItemStack> readyItems(UUID ownerId) {
        return ownerId != null && containerStates.get(ownerId) == ContainerState.READY ? cache.get(ownerId) : null;
    }

    private synchronized long nextSession(UUID ownerId) {
        long session = ++sessionCounter;
        sessions.put(ownerId, session);
        return session;
    }

    private void loadPlayerAsync(UUID ownerId, long session) {
        PlayerContainerLoadResult result;
        try {
            result = storage.load(ownerId);
        } catch (RuntimeException exception) {
            result = PlayerContainerLoadResult.failure(exception.getMessage());
        }
        List<ItemStack> recovered = null;
        if (result.successful()) {
            try {
                recovered = recoveryJournal.read(ownerId);
            } catch (java.io.IOException exception) {
                result = PlayerContainerLoadResult.failure("Recovery journal could not be read: " + exception.getMessage());
            }
        }
        PlayerContainerLoadResult finalResult = result;
        List<ItemStack> finalRecovered = recovered;
        scheduleMain(() -> publishLoadedPlayer(ownerId, session, finalResult, finalRecovered));
    }

    private synchronized void publishLoadedPlayer(UUID ownerId, long session, PlayerContainerLoadResult result, List<ItemStack> recovered) {
        if (!running || sessions.getOrDefault(ownerId, -1L) != session || containerStates.get(ownerId) != ContainerState.LOADING
                || Bukkit.getPlayer(ownerId) == null) return;
        if (!result.successful()) {
            containerStates.put(ownerId, ContainerState.LOAD_FAILED);
            plugin.getLogger().severe("Failed to load container for " + ownerId + ": " + result.error());
            return;
        }
        List<ItemStack> items = cloneItems(recovered == null ? result.items() : recovered);
        cache.put(ownerId, items);
        containerStates.put(ownerId, ContainerState.READY);
        ContainerDebugLogger.containerLoaded(ownerId, items);
        if (recovered != null) {
            markDirty(ownerId);
            submitSingleSave(ownerId, session, new DirtyContainerSnapshot(cloneItems(items), dirtyVersions.get(ownerId)));
        }
    }

    private void submitSingleSave(UUID ownerId, long session, DirtyContainerSnapshot snapshot) {
        synchronized (this) {
            inFlightVersions.put(ownerId, snapshot.version());
        }
        try {
        persistenceExecutor.execute(() -> {
            boolean saved;
            try {
                saved = storage.save(ownerId, snapshot.items());
            } catch (RuntimeException exception) {
                saved = false;
            }
            boolean saveSucceeded = saved;
            if (saveSucceeded) deleteRecoveryJournal(ownerId);
            scheduleMain(() -> {
                if (saveSucceeded) markSaved(ownerId, snapshot.version());
                else ContainerDebugLogger.containerSaveFailed(ownerId, ContainerDebugLogger.SaveCause.AUTO_SAVE);
                synchronized (ContainerManager.this) {
                    if (inFlightVersions.getOrDefault(ownerId, -1L) == snapshot.version()) inFlightVersions.remove(ownerId);
                }
            });
        });
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            synchronized (this) {
                if (inFlightVersions.getOrDefault(ownerId, -1L) == snapshot.version()) inFlightVersions.remove(ownerId);
            }
        }
    }

    private void deleteRecoveryJournal(UUID ownerId) {
        // During plugin unload the journal is the authoritative snapshot. An older
        // in-flight SQL save must never delete a journal written by onDisable().
        if (!running) return;
        try {
            recoveryJournal.delete(ownerId);
        } catch (java.io.IOException exception) {
            plugin.getLogger().warning("Failed to remove container recovery journal for " + ownerId + ": " + exception.getMessage());
        }
    }

    private void scheduleMain(Runnable task) {
        if (!running) return;
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException ignored) {
        }
    }

    private synchronized void evict(UUID ownerId) {
        cache.remove(ownerId);
        dirtyVersions.remove(ownerId);
        containerVersions.remove(ownerId);
        inFlightVersions.remove(ownerId);
        containerStates.remove(ownerId);
        sessions.remove(ownerId);
        bulkOperationOwners.remove(ownerId);
        ContainerDebugLogger.clearPlayer(ownerId);
    }

    private ThreadFactory persistenceThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "VContainer-persistence");
            thread.setDaemon(true);
            return thread;
        };
    }

    public synchronized ContainerState getContainerState(UUID ownerId) {
        return containerStates.getOrDefault(ownerId, ContainerState.OFFLINE);
    }

    public synchronized boolean isContainerLoaded(UUID ownerId) {
        return containerStates.get(ownerId) == ContainerState.READY;
    }

    public enum ContainerState {
        OFFLINE,
        LOADING,
        READY,
        UNLOADING,
        LOAD_FAILED
    }

    private record DirtyContainerSnapshot(List<ItemStack> items, long version) {
    }

    public static final class BatchTakeOperation {
        private final UUID ownerId;
        private final ItemStack target;
        private final int requested;
        private final boolean targetHasMeta;
        private final org.bukkit.inventory.meta.ItemMeta targetMeta;
        private final Map<ItemStack, Integer> removals = new IdentityHashMap<>();
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
