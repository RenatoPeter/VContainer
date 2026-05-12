package hu.vzone.vcontainer.utils;

import com.destroystokyo.paper.profile.ProfileProperty;
import hu.vzone.vcontainer.VContainer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ConfigItemBuilder {

    private ConfigItemBuilder() {
    }

    public static ItemStack build(VContainer plugin, ConfigurationSection section, Material fallbackMaterial, Map<String, String> placeholders) {
        if (section == null) {
            return new ItemStack(fallbackMaterial);
        }

        String materialName = getString(section, "Material", "material", fallbackMaterial.name());
        ItemStack item = getHeadDatabaseItem(materialName);
        if (item == null) {
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                plugin.getLogger().warning("Invalid configured item material '" + materialName + "' at " + section.getCurrentPath());
                material = fallbackMaterial;
            }
            item = new ItemStack(material);
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        String name = getString(section, "Name", "display_name", null);
        if (name == null) {
            name = section.getString("name");
        }
        if (name != null) {
            meta.setDisplayName(format(name, placeholders));
        }

        List<String> lore = getStringList(section, "Lore", "lore");
        if (!lore.isEmpty()) {
            List<String> formattedLore = new ArrayList<>();
            for (String line : lore) {
                formattedLore.add(format(line, placeholders));
            }
            meta.setLore(formattedLore);
        }

        if (getBoolean(section, "Unbreakable", "unbreakable", false)) {
            meta.setUnbreakable(true);
        }

        int customModelData = getInt(section, "CustomModelData", "custom_model_data", -1);
        if (customModelData < 0) {
            customModelData = section.getInt("model_data", -1);
        }
        if (customModelData >= 0) {
            meta.setCustomModelData(customModelData);
        }

        int maxStackSize = getInt(section, "MaxStackSize", "max_stack_size", -1);
        if (maxStackSize > 0) {
            try {
                meta.setMaxStackSize(maxStackSize);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid MaxStackSize at " + section.getCurrentPath() + ": " + maxStackSize);
            }
        }

        if (section.contains("Glow") || section.contains("glow")) {
            meta.setEnchantmentGlintOverride(getBoolean(section, "Glow", "glow", false));
        }

        applyItemFlags(plugin, section, meta);
        applyEnchantments(plugin, section, meta);
        applyAttributes(plugin, section, meta);
        applyTooltipStyle(plugin, section, meta);

        if (meta instanceof SkullMeta skullMeta) {
            applyHeadTexture(plugin, section, skullMeta, placeholders);
        }

        item.setItemMeta(meta);
        return item;
    }

    private static void applyItemFlags(VContainer plugin, ConfigurationSection section, ItemMeta meta) {
        for (String flagName : getStringList(section, "ItemFlags", "item_flags")) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(flagName.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid ItemFlag at " + section.getCurrentPath() + ": " + flagName);
            }
        }
    }

    private static void applyEnchantments(VContainer plugin, ConfigurationSection section, ItemMeta meta) {
        Object raw = get(section, "Enchantments", "enchants");
        if (raw instanceof ConfigurationSection enchantSection) {
            for (String key : enchantSection.getKeys(false)) {
                addEnchant(plugin, section, meta, key, enchantSection.getInt(key, 1));
            }
            return;
        }

        for (String line : getStringList(section, "Enchantments", "enchants")) {
            String[] parts = line.split(":", 3);
            if (parts.length < 1) continue;

            String enchantName = parts.length == 3 ? parts[0] + ":" + parts[1] : parts[0];
            int level = 1;
            if (parts.length >= 2) {
                try {
                    level = Integer.parseInt(parts[parts.length - 1]);
                } catch (NumberFormatException ignored) {
                }
            }
            addEnchant(plugin, section, meta, enchantName, level);
        }
    }

    private static void addEnchant(VContainer plugin, ConfigurationSection section, ItemMeta meta, String name, int level) {
        Enchantment enchantment = Enchantment.getByKey(parseKey(name));
        if (enchantment == null) {
            plugin.getLogger().warning("Invalid enchantment at " + section.getCurrentPath() + ": " + name);
            return;
        }
        meta.addEnchant(enchantment, Math.max(1, level), true);
    }

    private static void applyAttributes(VContainer plugin, ConfigurationSection section, ItemMeta meta) {
        Object raw = get(section, "Attributes", "attributes");
        if (!(raw instanceof List<?> list)) return;

        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) continue;

            String attributeName = stringValue(map, "Attribute", "attribute");
            Attribute attribute = parseAttribute(attributeName);
            if (attribute == null) {
                plugin.getLogger().warning("Invalid attribute at " + section.getCurrentPath() + ": " + attributeName);
                continue;
            }

            double amount = doubleValue(map, "Amount", "amount", 0.0D);
            AttributeModifier.Operation operation = parseOperation(stringValue(map, "Operation", "operation"));
            EquipmentSlotGroup slot = parseSlotGroup(stringValue(map, "Slot", "slot"));
            String keyName = stringValue(map, "Key", "key");
            if (keyName == null || keyName.isBlank()) {
                keyName = "vcontainer:" + attribute.name().toLowerCase(Locale.ROOT) + "_" + UUID.randomUUID();
            }

            NamespacedKey key = NamespacedKey.fromString(keyName.toLowerCase(Locale.ROOT));
            if (key == null) {
                plugin.getLogger().warning("Invalid attribute modifier key at " + section.getCurrentPath() + ": " + keyName);
                continue;
            }

            meta.addAttributeModifier(attribute, new AttributeModifier(key, amount, operation, slot));
        }
    }

    private static void applyTooltipStyle(VContainer plugin, ConfigurationSection section, ItemMeta meta) {
        String value = getString(section, "TooltipStyle", "tooltip_style", "");
        if (value.isBlank()) return;

        NamespacedKey key = NamespacedKey.fromString(value.toLowerCase(Locale.ROOT));
        if (key == null) {
            plugin.getLogger().warning("Invalid TooltipStyle at " + section.getCurrentPath() + ": " + value);
            return;
        }

        try {
            Method method = meta.getClass().getMethod("setTooltipStyle", NamespacedKey.class);
            method.invoke(meta, key);
        } catch (NoSuchMethodException ignored) {
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Could not apply TooltipStyle at " + section.getCurrentPath() + ": " + value);
        }
    }

    private static void applyHeadTexture(VContainer plugin, ConfigurationSection section, SkullMeta skullMeta, Map<String, String> placeholders) {
        String texture = getString(section, "Texture", "texture", "");
        texture = replace(texture, placeholders).trim();
        if (texture.isEmpty()) return;

        try {
            com.destroystokyo.paper.profile.PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), null);
            profile.setProperty(new ProfileProperty("textures", texture));
            skullMeta.setPlayerProfile(profile);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Invalid head texture at " + section.getCurrentPath());
        }
    }

    private static ItemStack getHeadDatabaseItem(String materialName) {
        if (materialName == null || !materialName.toUpperCase(Locale.ROOT).startsWith("HDB-")) return null;
        String id = materialName.substring("HDB-".length());
        if (Bukkit.getPluginManager().getPlugin("HeadDatabase") == null) return new ItemStack(Material.PLAYER_HEAD);

        try {
            Class<?> apiClass = Class.forName("me.arcaniax.hdb.api.HeadDatabaseAPI");
            Object api = apiClass.getConstructor().newInstance();
            Object result = apiClass.getMethod("getItemHead", String.class).invoke(api, id);
            if (result instanceof ItemStack item) return item;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return new ItemStack(Material.PLAYER_HEAD);
    }

    private static Attribute parseAttribute(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return Attribute.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static AttributeModifier.Operation parseOperation(String name) {
        if (name == null || name.isBlank()) return AttributeModifier.Operation.ADD_NUMBER;
        try {
            return AttributeModifier.Operation.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AttributeModifier.Operation.ADD_NUMBER;
        }
    }

    private static EquipmentSlotGroup parseSlotGroup(String name) {
        if (name == null || name.isBlank()) return EquipmentSlotGroup.ANY;
        EquipmentSlotGroup group = EquipmentSlotGroup.getByName(name.toLowerCase(Locale.ROOT));
        return group == null ? EquipmentSlotGroup.ANY : group;
    }

    private static NamespacedKey parseKey(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!normalized.contains(":")) normalized = "minecraft:" + normalized;
        return NamespacedKey.fromString(normalized);
    }

    private static String format(String text, Map<String, String> placeholders) {
        return VContainer.formatMessage(replace(text, placeholders));
    }

    private static String replace(String text, Map<String, String> placeholders) {
        String result = text == null ? "" : text;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static Object get(ConfigurationSection section, String primary, String secondary) {
        if (section.contains(primary)) return section.get(primary);
        return section.get(secondary);
    }

    private static String getString(ConfigurationSection section, String primary, String secondary, String fallback) {
        if (section.contains(primary)) return section.getString(primary, fallback);
        return section.getString(secondary, fallback);
    }

    private static int getInt(ConfigurationSection section, String primary, String secondary, int fallback) {
        if (section.contains(primary)) return section.getInt(primary, fallback);
        return section.getInt(secondary, fallback);
    }

    private static boolean getBoolean(ConfigurationSection section, String primary, String secondary, boolean fallback) {
        if (section.contains(primary)) return section.getBoolean(primary, fallback);
        return section.getBoolean(secondary, fallback);
    }

    private static List<String> getStringList(ConfigurationSection section, String primary, String secondary) {
        List<String> values = section.getStringList(primary);
        return values.isEmpty() ? section.getStringList(secondary) : values;
    }

    private static String stringValue(Map<?, ?> map, String primary, String secondary) {
        Object value = map.containsKey(primary) ? map.get(primary) : map.get(secondary);
        return value == null ? null : String.valueOf(value);
    }

    private static double doubleValue(Map<?, ?> map, String primary, String secondary, double fallback) {
        Object value = map.containsKey(primary) ? map.get(primary) : map.get(secondary);
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
