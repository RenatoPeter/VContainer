package hu.vzone.vContainer.utils;

import hu.vzone.vContainer.VContainer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class Buttons {

    public static ItemStack buildButton(String path) {
        ConfigurationSection section = VContainer.getInstance().getConfig().getConfigurationSection("buttons." + path);
        if (section == null) return null;

        String matName = section.getString("material", "BARRIER");
        Material material = Material.matchMaterial(matName);
        if (material == null) material = Material.BARRIER;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(section.getString("name", "§cUnnamed"));
            List<String> lore = section.getStringList("lore");
            if (lore == null) lore = new ArrayList<>();
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static int getButtonSlot(String path) {
        ConfigurationSection section = VContainer.getInstance().getConfig().getConfigurationSection("buttons." + path);
        if (section == null) return -1;
        // pl. slot: 45+3 → értékeljük ki
        String slotExpr = section.getString("slot", "0");
        try {
            if (slotExpr.contains("+")) {
                String[] parts = slotExpr.split("\\+");
                return Integer.parseInt(parts[0].trim()) + Integer.parseInt(parts[1].trim());
            }
            return Integer.parseInt(slotExpr.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
