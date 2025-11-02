package hu.vzone.vcontainer.managers;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.utils.ItemUtils;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

public class ContainerManager {
    private final VContainer plugin;
    private final Map<UUID, List<ItemStack>> cache = new HashMap<>();

    public ContainerManager(VContainer plugin) {
        this.plugin = plugin;
    }

    // --- Required API methods ---
    public void addItemToContainer(Player player, ItemStack item) {
        List<ItemStack> list = getOrLoad(player);

        boolean stackEnabled = plugin.getConfig().getBoolean("stack", true);
        int maxStack = plugin.getConfig().getInt("max-stack", 64);

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

            // Ha maradt fölös, új stacket hozunk létre
            while (amountToAdd > 0) {
                int split = Math.min(amountToAdd, Math.min(maxStack, item.getMaxStackSize()));
                ItemStack newStack = item.clone();
                newStack.setAmount(split);
                list.add(newStack);
                amountToAdd -= split;
            }
        } else {
            // Stackelés ki van kapcsolva
            list.add(item.clone());
        }

        save(player, list);
    }

    public void removeItemFromContainer(Player player, ItemStack target) {
        List<ItemStack> list = getOrLoad(player);

        for (Iterator<ItemStack> it = list.iterator(); it.hasNext();) {
            ItemStack current = it.next();
            if (current == null) continue;
            if (ItemUtils.isSameItemWithNBT(current, target)) {
                it.remove();
                break;
            }
        }

        save(player, list);
    }

    public List<ItemStack> getAllItemFromContainer(Player player) {
        return Collections.unmodifiableList(new ArrayList<>(getOrLoad(player)));
    }

    public boolean itemInContainer(Player player, ItemStack item) {
        List<ItemStack> list = getOrLoad(player);
        return list.stream().anyMatch(s -> ItemUtils.isSameItemWithNBT(s, item));
    }

    // --- Persistence ---
    private List<ItemStack> getOrLoad(Player player) {
        UUID id = player.getUniqueId();
        if (cache.containsKey(id)) return cache.get(id);
        List<ItemStack> list = loadFromDisk(id);
        cache.put(id, list);
        return list;
    }

    private void save(Player player, List<ItemStack> list) {
        UUID id = player.getUniqueId();
        cache.put(id, new ArrayList<>(list));
        saveToDisk(id, list);
    }

    public void clearContainer(Player player) {
        UUID id = player.getUniqueId();
        cache.put(id, new ArrayList<>());
        saveToDisk(id, new ArrayList<>());
    }

    // --- Disk ---
    private void saveToDisk(UUID id, List<ItemStack> list) {
        File file = new File(plugin.getPlayerDataFolder(), id.toString() + ".json");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            String base64 = ItemUtils.itemsToBase64(list);
            Map<String, String> wrapper = new HashMap<>();
            wrapper.put("items_base64", base64);
            String json = plugin.getGson().toJson(wrapper);
            fos.write(json.getBytes());
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save container for " + id + ": " + e.getMessage());
        }
    }

    private List<ItemStack> loadFromDisk(UUID id) {
        File file = new File(plugin.getPlayerDataFolder(), id.toString() + ".json");
        if (!file.exists()) return new ArrayList<>();
        try (Scanner scanner = new Scanner(file)) {
            StringBuilder jsonBuilder = new StringBuilder();
            while (scanner.hasNextLine()) {
                jsonBuilder.append(scanner.nextLine());
            }
            String json = jsonBuilder.toString();
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
}
