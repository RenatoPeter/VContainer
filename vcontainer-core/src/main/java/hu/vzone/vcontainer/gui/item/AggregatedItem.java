package hu.vzone.vcontainer.gui.item;

import org.bukkit.inventory.ItemStack;

/** A display-only item type. The template always retains the original item metadata. */
public record AggregatedItem(ItemStack template, int amount, String searchName) {
}
