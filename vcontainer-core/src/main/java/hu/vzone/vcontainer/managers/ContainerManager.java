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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ContainerManager {
    private static final long AUTO_SAVE_TICKS = 20L * 60L * 2L;

    private final VContainer plugin;
    private final ContainerStorage storage;
    private final Map<UUID, List<ItemStack>> cache = new HashMap<>();
    private final Map<UUID, Long> dirtyVersions = new HashMap<>();
    private final Map<UUID, Long> containerVersions = new HashMap<>();
    private final Object saveLock = new Object();
    private long mutationVersion;
    private BukkitTask autoSaveTask;
    private volatile boolean persistenceSuspended;
    private volatile boolean stackEnabled;
    private volatile int configuredMaxStack;

    public ContainerManager(VContainer plugin) {
        this.plugin = plugin;
        this.storage = createStorage(plugin);
        this.cache.putAll(cloneContainers(storage.loadAll()));
        reloadRuntimeSettings();
        this.autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::flushDirtySync,
                AUTO_SAVE_TICKS,
                AUTO_SAVE_TICKS
        );
        plugin.getLogger().info("Loaded " + cache.size() + " cached container(s) from " + StorageSettings.from(plugin).type() + " storage.");
    }

    public int addItemToContainer(Player player, ItemStack item) {
        return addItemToContainer(player.getUniqueId(), player, item);
    }

    public int addItemToContainer(UUID ownerId, Player actor, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return 0;

        ContainerAddItemEvent event = new ContainerAddItemEvent(actor, item);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return 0;

        return addItemToContainer(ownerId, item);
    }

    public synchronized int addItemToContainer(UUID ownerId, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return 0;

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
        return Collections.unmodifiableList(cloneItems(getOrCreate(ownerId)));
    }

    public synchronized List<ItemStack> getItemView(UUID ownerId) {
        return Collections.unmodifiableList(new ArrayList<>(getOrCreate(ownerId)));
    }

    public synchronized ItemStack peekFirstItem(UUID ownerId) {
        for (ItemStack item : getOrCreate(ownerId)) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;
            return item.clone();
        }
        return null;
    }

    public synchronized Map<UUID, List<ItemStack>> snapshotContainers() {
        return cloneContainers(cache);
    }

    public synchronized boolean itemInContainer(Player player, ItemStack item) {
        List<ItemStack> list = getOrCreate(player.getUniqueId());
        return list.stream().anyMatch(s -> ItemUtils.isSameItemWithNBT(s, item));
    }

    public void clearContainer(Player player) {
        clearContainer(player.getUniqueId());
    }

    public synchronized void clearContainer(UUID ownerId) {
        cache.put(ownerId, new ArrayList<>());
        markDirty(ownerId);
    }

    public void clearCacheFor(UUID playerId) {
        // Containers are loaded once and kept in memory so delayed saves cannot lose dirty data.
    }

    public void flushAllSync() {
        if (autoSaveTask != null) autoSaveTask.cancel();
        if (!persistenceSuspended) flushSync();
        storage.close();
    }

    public void flushSync() {
        if (persistenceSuspended) return;
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

    private synchronized List<ItemStack> getOrCreate(UUID id) {
        return cache.computeIfAbsent(id, ignored -> new ArrayList<>());
    }

    private synchronized void markDirty(UUID id) {
        long version = ++mutationVersion;
        dirtyVersions.put(id, version);
        containerVersions.put(id, version);
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
}
