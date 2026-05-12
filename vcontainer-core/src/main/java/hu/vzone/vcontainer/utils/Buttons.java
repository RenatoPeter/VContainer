package hu.vzone.vcontainer.utils;

import hu.vzone.vcontainer.VContainer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public class Buttons {

    public static ItemStack buildButton(String path) {
        ConfigurationSection section = VContainer.getInstance().getConfig().getConfigurationSection("buttons." + path);
        if (section == null) return new ItemStack(Material.BARRIER);

        return ConfigItemBuilder.build(VContainer.getInstance(), section, Material.BARRIER, Map.of());
    }

    public static int getButtonSlot(String path) {
        ConfigurationSection section = VContainer.getInstance().getConfig().getConfigurationSection("buttons." + path);
        if (section == null) return -1;

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
