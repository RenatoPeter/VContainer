package hu.vzone.vcontainer.utils;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public class InventoryFill {
    public static void fillBottomRow(Inventory inv) {
        int rows = inv.getSize() / 9;
        int r = rows - 1;
        for (int c = 0; c < 9; c++) {
            int slot = r * 9 + c;
            if (inv.getItem(slot) != null) continue;
            ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
            ItemMeta m = pane.getItemMeta();
            m.setDisplayName(" ");
            m.setLore(List.of());
            pane.setItemMeta(m);
            inv.setItem(slot, pane);
        }
    }
}
