package hu.vzone.vcontainer.managers;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.api.events.ContainerAddItemEvent;
import hu.vzone.vcontainer.utils.ItemUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private final VContainer plugin;
    private final Map<UUID, List<ItemStack>> cache = new HashMap<>();
    private final Set<UUID> saving = new HashSet<>();
    private final Set<UUID> saveAgain = new HashSet<>();

    public ContainerManager(VContainer plugin) {
        this.plugin = plugin;
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

    public void addItemToContainer(UUID ownerId, ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() <= 0) return;

        List<ItemStack> list = getOrLoad(ownerId);
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

        save(ownerId, list);
    }

    public void removeItemFromContainer(Player player, ItemStack target) {
        removeItemFromContainer(player.getUniqueId(), target);
    }

    public void removeItemFromContainer(UUID ownerId, ItemStack target) {
        takeItemFromContainer(ownerId, target, target == null ? 0 : target.getAmount());
    }

    public int takeItemFromContainer(UUID ownerId, ItemStack target, int amount) {
        if (target == null || target.getType().isAir() || amount <= 0) return 0;

        List<ItemStack> list = getOrLoad(ownerId);
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
        if (removed > 0) save(ownerId, list);
        return removed;
    }

    public List<ItemStack> getAllItemFromContainer(Player player) {
        return getAllItemFromContainer(player.getUniqueId());
    }

    public List<ItemStack> getAllItemFromContainer(UUID ownerId) {
        return Collections.unmodifiableList(cloneItems(getOrLoad(ownerId)));
    }

    public boolean itemInContainer(Player player, ItemStack item) {
        List<ItemStack> list = getOrLoad(player.getUniqueId());
        return list.stream().anyMatch(s -> ItemUtils.isSameItemWithNBT(s, item));
    }

    public void clearContainer(Player player) {
        clearContainer(player.getUniqueId());
    }

    public void clearContainer(UUID ownerId) {
        cache.put(ownerId, new ArrayList<>());
        queueSave(ownerId);
    }

    public void clearCacheFor(UUID playerId) {
        cache.remove(playerId);
    }

    public void flushAllSync() {
        for (Map.Entry<UUID, List<ItemStack>> entry : cache.entrySet()) {
            saveToDisk(entry.getKey(), cloneItems(entry.getValue()));
        }
    }

    private List<ItemStack> getOrLoad(UUID id) {
        if (cache.containsKey(id)) return cache.get(id);

        List<ItemStack> list = loadFromDisk(id);
        cache.put(id, list);
        return list;
    }

    private void save(UUID id, List<ItemStack> list) {
        cache.put(id, cloneItems(list));
        queueSave(id);
    }

    private void queueSave(UUID id) {
        List<ItemStack> snapshot = cloneItems(cache.getOrDefault(id, new ArrayList<>()));

        synchronized (saving) {
            if (saving.contains(id)) {
                saveAgain.add(id);
                return;
            }
            saving.add(id);
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            saveToDisk(id, snapshot);

            boolean repeat;
            synchronized (saving) {
                repeat = saveAgain.remove(id);
                saving.remove(id);
            }

            if (repeat && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> queueSave(id));
            }
        });
    }

    private void saveToDisk(UUID id, List<ItemStack> list) {
        File file = new File(plugin.getPlayerDataFolder(), id + ".json");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            String base64 = ItemUtils.itemsToBase64(list);
            Map<String, String> wrapper = new HashMap<>();
            wrapper.put("items_base64", base64);
            String json = plugin.getGson().toJson(wrapper);
            fos.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save container for " + id + ": " + e.getMessage());
        }
    }

    private List<ItemStack> loadFromDisk(UUID id) {
        File file = new File(plugin.getPlayerDataFolder(), id + ".json");
        if (!file.exists()) return new ArrayList<>();

        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Map<String, String> wrapper = plugin.getGson().fromJson(json, Map.class);
            if (wrapper == null) return new ArrayList<>();

            String base64 = wrapper.get("items_base64");
            if (base64 == null || base64.isEmpty()) return new ArrayList<>();

            return ItemUtils.itemsFromBase64(base64);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load container for " + id + ": " + e.getMessage());
            return new ArrayList<>();
        }
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
