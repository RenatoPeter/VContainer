package hu.vzone.vcontainer.utils;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.items.MythicItem;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class ItemDisplayNames {

    private ItemDisplayNames() {
    }

    public static String resolve(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "Air";
        }

        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return meta.getDisplayName();
        }

        String mythicName = resolveMythicName(item);
        if (mythicName != null && !mythicName.isBlank()) {
            return mythicName;
        }

        return formatMaterialName(item.getType());
    }

    private static String resolveMythicName(ItemStack item) {
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) {
            return null;
        }

        try {
            MythicBukkit mythic = MythicBukkit.inst();
            if (mythic == null || mythic.getItemManager() == null || !mythic.getItemManager().isMythicItem(item)) {
                return null;
            }

            String type = mythic.getItemManager().getMythicTypeFromItem(item);
            if (type == null || type.isBlank()) {
                return null;
            }

            MythicItem mythicItem = mythic.getItemManager().getItem(type).orElse(null);
            if (mythicItem == null) {
                return null;
            }

            String displayName = mythicItem.getDisplayName();
            if (displayName != null && !displayName.isBlank()) {
                return ChatColor.translateAlternateColorCodes('&', displayName);
            }

            return formatMaterialName(mythicItem.getMaterial());
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String formatMaterialName(Material material) {
        if (material == null) {
            return "Unknown Item";
        }

        return switch (material) {
            case REDSTONE -> "Redstone Dust";
            default -> {
                String[] parts = material.name().toLowerCase().split("_");
                StringBuilder builder = new StringBuilder();
                for (String part : parts) {
                    if (part.isEmpty()) continue;
                    if (builder.length() > 0) builder.append(' ');
                    builder.append(Character.toUpperCase(part.charAt(0)));
                    if (part.length() > 1) {
                        builder.append(part.substring(1));
                    }
                }
                yield builder.toString();
            }
        };
    }
}
