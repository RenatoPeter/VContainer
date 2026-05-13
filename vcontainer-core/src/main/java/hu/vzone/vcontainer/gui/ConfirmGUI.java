package hu.vzone.vcontainer.gui;

import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import hu.vzone.vcontainer.VContainer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class ConfirmGUI {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private ConfirmGUI() {
    }

    public static void open(Player player, String title, Runnable onConfirm) {
        Gui gui = Gui.gui()
                .title(LEGACY.deserialize(VContainer.formatMessage(player, title)))
                .rows(3)
                .disableAllInteractions()
                .create();

        gui.setItem(11, ItemBuilder.from(button(
                Material.LIME_CONCRETE,
                "&aConfirm",
                List.of("&7This action cannot always be undone.")
        )).asGuiItem(event -> {
            event.setCancelled(true);
            player.closeInventory();
            onConfirm.run();
        }));

        gui.setItem(15, ItemBuilder.from(button(
                Material.RED_CONCRETE,
                "&cCancel",
                List.of("&7Return without changing anything.")
        )).asGuiItem(event -> {
            event.setCancelled(true);
            player.closeInventory();
        }));

        gui.open(player);
    }

    private static ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(VContainer.formatMessage(name));
            meta.setLore(lore.stream().map(VContainer::formatMessage).toList());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            item.setItemMeta(meta);
        }
        return item;
    }
}
