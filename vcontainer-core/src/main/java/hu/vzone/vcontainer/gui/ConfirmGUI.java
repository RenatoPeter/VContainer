package hu.vzone.vcontainer.gui;

import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.utils.ConfigItemBuilder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.List;

public final class ConfirmGUI {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private ConfirmGUI() {
    }

    public static void open(Player player, String title, Runnable onConfirm) {
        open(player, null, title, onConfirm);
    }

    public static void open(Player player, String context, String fallbackTitle, Runnable onConfirm) {
        VContainer plugin = VContainer.getInstance();
        FileConfiguration menu = plugin.getMenuConfig("confirm");
        int rows = Math.max(1, Math.min(6, menu == null ? 3 : menu.getInt("rows", 3)));
        String configuredTitle = menu == null ? "%title%" : menu.getString("title", "%title%");
        String title = fallbackTitle;
        if (menu != null && context != null && !context.isBlank()) {
            title = menu.getString("titles." + context, fallbackTitle);
        }

        Gui gui = Gui.gui()
                .title(LEGACY.deserialize(VContainer.formatMessage(player, configuredTitle.replace("%title%", title))))
                .rows(rows)
                .disableAllInteractions()
                .create();

        ItemStack filler = createItem(menu, "filler", Material.BLACK_STAINED_GLASS_PANE, Map.of("title", title));
        int[] fillerSlots = itemSlots(menu, "filler", new int[0]);
        for (int slot : fillerSlots) {
            gui.setItem(slot, ItemBuilder.from(filler).asGuiItem(event -> event.setCancelled(true)));
        }

        gui.setItem(firstSlot(menu, "confirm", 11), ItemBuilder.from(createItem(
                menu,
                "confirm",
                Material.LIME_CONCRETE,
                Map.of("title", title)
        )).asGuiItem(event -> {
            event.setCancelled(true);
            player.closeInventory();
            onConfirm.run();
        }));

        gui.setItem(firstSlot(menu, "cancel", 15), ItemBuilder.from(createItem(
                menu,
                "cancel",
                Material.RED_CONCRETE,
                Map.of("title", title)
        )).asGuiItem(event -> {
            event.setCancelled(true);
            player.closeInventory();
        }));

        gui.open(player);
    }

    private static ItemStack createItem(FileConfiguration menu, String key, Material fallback, Map<String, String> placeholders) {
        if (menu == null) return new ItemStack(fallback);
        ConfigurationSection items = menu.getConfigurationSection("items");
        if (items == null) return new ItemStack(fallback);
        ConfigurationSection section = items.getConfigurationSection(key);
        if (section == null) return new ItemStack(fallback);
        return ConfigItemBuilder.build(VContainer.getInstance(), section, fallback, placeholders);
    }

    private static int firstSlot(FileConfiguration menu, String key, int fallback) {
        int[] slots = itemSlots(menu, key, new int[]{fallback});
        return slots.length == 0 ? fallback : slots[0];
    }

    private static int[] itemSlots(FileConfiguration menu, String key, int[] fallback) {
        if (menu == null) return fallback;
        ConfigurationSection items = menu.getConfigurationSection("items");
        if (items == null) return fallback;
        ConfigurationSection section = items.getConfigurationSection(key);
        if (section == null) return fallback;

        List<Integer> slots = new java.util.ArrayList<>();
        if (section.contains("Slot")) {
            slots.add(section.getInt("Slot"));
        } else if (section.contains("slot")) {
            slots.add(section.getInt("slot"));
        }
        List<Integer> many = section.getIntegerList("Slots");
        if (many.isEmpty()) {
            many = section.getIntegerList("slots");
        }
        slots.addAll(many);
        if (slots.isEmpty()) return fallback;
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }
}
