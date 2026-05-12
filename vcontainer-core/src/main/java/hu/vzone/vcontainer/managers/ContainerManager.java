package hu.vzone.vcontainer.managers;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.api.events.ContainerAddItemEvent;
import hu.vzone.vcontainer.storage.ContainerStorage;
import hu.vzone.vcontainer.storage.LocalContainerStorage;
import hu.vzone.vcontainer.storage.SqlContainerStorage;
import hu.vzone.vcontainer.storage.StorageSettings;
import hu.vzone.vcontainer.utils.ItemUtils;
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

public class ContainerManager {
    private static final long AUTO_SAVE_TICKS = 20L * 60L * 2L;

    private final VContainer plugin;
    private final ContainerStorage storage;
    private final Map<UUID, List<ItemStack>> cache = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final Object saveLock = new Object();
    private BukkitTask autoSaveTask;

    public ContainerManager(VContainer plugin) {
        this.plugin = plugin;
        this.storage = createStorage(plugin);
        this.cache.putAll(cloneContainers(storage.loadAll()));
        this.autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::flushDirtySync,
                AUTO_SAVE_TICKS,
                AUTO_SAVE_TICKS
        );
        plugin.getLogger().info("Loaded " + cache.size() + " cached container(s) from " + StorageSettings.from(plugin).type() + " storage.");
    }

    public void addItemToContainer(Player player, ItemStack item) {
        addItemToContainer(player.getUniqueId(), player, item);
    }

    public void addItemToContainer(UUID ownerId, Player actor, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return;

        ContainerAddItemEvent event = new ContainerAddItemEvent(actor, item);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        addItemToContainer(ownerId, item);
    }

    public synchronized void addItemToContainer(UUID ownerId, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return;

        List<ItemStack> list = getOrCreate(ownerId);
        boolean stackEnabled = plugin.getConfig().getBoolean("stack", true);
        int maxStack = Math.max(1, plugin.getConfig().getInt("max-stack", 64));

        if (stackEnabled) {
            int amountToAdd = item.getAmount();

            for (ItemStack current : list) {
                if (ItemUtils.isSameItemWithNBT(current, item)) {
                    int space = Math.min(maxStack, current.getMaxStackSize()) - current.getAmount();
                    if (space > 0) {
                        int add = Math.min(space, amountToAdd);
                        current.setAmount(current.getAmount() + add);
                        amountToAdd -= add;
                    }
                    if (amountToAdd <= 0) break;
                }
            }

            while (amountToAdd > 0) {
                int split = Math.min(amountToAdd, Math.min(maxStack, item.getMaxStackSize()));
                ItemStack newStack = item.clone();
                newStack.setAmount(split);
                list.add(newStack);
                amountToAdd -= split;
            }
        } else {
            list.add(item.clone());
        }

        markDirty(ownerId);
    }

    public void removeItemFromContainer(Player player, ItemStack target) {
        removeItemFromContainer(player.getUniqueId(), target);
    }

    public void removeItemFromContainer(UUID ownerId, ItemStack target) {
        takeItemFromContainer(ownerId, target, target == null ? 0 : target.getAmount());
    }

    public synchronized int takeItemFromContainer(UUID ownerId, ItemStack target, int amount) {
        if (target == null || target.getType().isAir() || amount <= 0) return 0;

        List<ItemStack> list = getOrCreate(ownerId);
        int remaining = amount;

        for (Iterator<ItemStack> it = list.iterator(); it.hasNext();) {
            ItemStack current = it.next();
            if (current == null) continue;
            if (ItemUtils.isSameItemWithNBT(current, target)) {
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
        flushSync();
        storage.close();
    }

    public void flushSync() {
        synchronized (saveLock) {
            flushDirtySync();
        }
    }

    private synchronized List<ItemStack> getOrCreate(UUID id) {
        return cache.computeIfAbsent(id, ignored -> new ArrayList<>());
    }

    private synchronized void markDirty(UUID id) {
        dirty.add(id);
    }

    private void flushDirtySync() {
        synchronized (saveLock) {
            Map<UUID, List<ItemStack>> snapshots = drainDirtySnapshots();
            for (Map.Entry<UUID, List<ItemStack>> entry : snapshots.entrySet()) {
                storage.save(entry.getKey(), entry.getValue());
            }
        }
    }

    private synchronized Map<UUID, List<ItemStack>> drainDirtySnapshots() {
        Map<UUID, List<ItemStack>> snapshots = new HashMap<>();
        for (UUID ownerId : dirty) {
            snapshots.put(ownerId, cloneItems(cache.getOrDefault(ownerId, new ArrayList<>())));
        }
        dirty.clear();
        return snapshots;
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
}
