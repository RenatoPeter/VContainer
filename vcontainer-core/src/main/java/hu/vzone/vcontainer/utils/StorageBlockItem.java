package hu.vzone.vcontainer.utils;

import hu.vzone.vcontainer.VContainer;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import org.bukkit.Material;

import java.util.Map;

public final class StorageBlockItem {
    private static final String KEY_NAME = "storage_block_item";

    private StorageBlockItem() {
    }

    public static ItemStack build(VContainer plugin, int amount) {
        ItemStack item = ConfigItemBuilder.build(
                plugin,
                plugin.getConfig().getConfigurationSection("storage-block.item"),
                Material.SCULK_SHRIEKER,
                Map.of()
        );
        item.setAmount(Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(key(plugin), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isStorageBlockItem(VContainer plugin, ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(key(plugin), PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    public static NamespacedKey key(VContainer plugin) {
        return new NamespacedKey(plugin, KEY_NAME);
    }
}
