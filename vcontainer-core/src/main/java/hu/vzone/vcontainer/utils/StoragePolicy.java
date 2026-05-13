package hu.vzone.vcontainer.utils;

import hu.vzone.vcontainer.VContainer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

import java.util.List;
import java.util.Locale;

public final class StoragePolicy {

    private StoragePolicy() {
    }

    public static Result canAdd(VContainer plugin, List<ItemStack> currentItems, ItemStack incoming) {
        if (incoming == null || incoming.getType().isAir()) return Result.deny("air");
        if (!plugin.getConfig().getBoolean("storage-limits.enabled", false)) return Result.allow();

        if (isMaterialBlocked(plugin, incoming.getType())) return Result.deny("blocked-material");
        if (hasBlockedPdc(plugin, incoming)) return Result.deny("blocked-pdc");
        if (plugin.getConfig().getBoolean("storage-limits.block-items-with-nbt", false) && incoming.hasItemMeta()) {
            return Result.deny("nbt-blocked");
        }

        int maxTotal = plugin.getConfig().getInt("storage-limits.max-total-items", -1);
        if (maxTotal >= 0 && totalAmount(currentItems) + incoming.getAmount() > maxTotal) {
            return Result.deny("max-total-items");
        }

        int maxUnique = plugin.getConfig().getInt("storage-limits.max-unique-items", -1);
        if (maxUnique >= 0 && isNewUnique(currentItems, incoming) && uniqueCount(currentItems) + 1 > maxUnique) {
            return Result.deny("max-unique-items");
        }

        return Result.allow();
    }

    private static boolean isMaterialBlocked(VContainer plugin, Material material) {
        for (String name : plugin.getConfig().getStringList("storage-limits.blocked-materials")) {
            Material blocked = Material.matchMaterial(name);
            if (blocked == material) return true;
        }
        return false;
    }

    private static boolean hasBlockedPdc(VContainer plugin, ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        PersistentDataContainer container = meta.getPersistentDataContainer();
        for (String raw : plugin.getConfig().getStringList("storage-limits.blocked-persistent-data-keys")) {
            NamespacedKey key = NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT));
            if (key != null && container.has(key)) return true;
        }
        return false;
    }

    private static int totalAmount(List<ItemStack> items) {
        int total = 0;
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) total += item.getAmount();
        }
        return total;
    }

    private static int uniqueCount(List<ItemStack> items) {
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (item == null || item.getType().isAir()) continue;
            boolean seen = false;
            for (int j = 0; j < i; j++) {
                if (ItemUtils.isSameItemWithNBT(items.get(j), item)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) count++;
        }
        return count;
    }

    private static boolean isNewUnique(List<ItemStack> items, ItemStack incoming) {
        for (ItemStack item : items) {
            if (ItemUtils.isSameItemWithNBT(item, incoming)) return false;
        }
        return true;
    }

    public record Result(boolean allowed, String reason) {
        private static Result allow() {
            return new Result(true, "");
        }

        private static Result deny(String reason) {
            return new Result(false, reason);
        }
    }
}
