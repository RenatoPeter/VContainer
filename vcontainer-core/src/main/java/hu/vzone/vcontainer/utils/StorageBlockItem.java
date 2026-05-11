package hu.vzone.vcontainer.utils;

import hu.vzone.vcontainer.VContainer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public final class StorageBlockItem {
    private static final String KEY_NAME = "storage_block_item";

    private StorageBlockItem() {
    }

    public static ItemStack build(VContainer plugin, int amount) {
        Material material = Material.matchMaterial(plugin.getConfig().getString("storage-block.item.material", "SCULK_SHRIEKER"));
        if (material == null || !material.isBlock()) material = Material.SCULK_SHRIEKER;

        ItemStack item = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(VContainer.formatMessage(plugin.getConfig().getString("storage-block.item.name", "&bPersonal Storage Block")));

            List<String> lore = new ArrayList<>();
            for (String line : plugin.getConfig().getStringList("storage-block.item.lore")) {
                lore.add(VContainer.formatMessage(line));
            }
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
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
