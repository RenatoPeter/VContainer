package hu.vzone.vContainer.gui;

import hu.vzone.vContainer.VContainer;
import hu.vzone.vContainer.managers.ContainerManager;
import hu.vzone.vContainer.utils.Buttons;
import hu.vzone.vContainer.utils.InventoryFill;
import hu.vzone.vContainer.utils.PlayerViewingCache;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class ContainerGUI {
    private static final int ROWS = 6;
    private static final int SLOTS = ROWS * 9; // 54
    private static final int CONTROL_ROW_INDEX = ROWS - 1; // last row is control


    // Slot indices where removable items will be placed (configurable later)
    private static final int[] defaultContentSlots = new int[45];
    static {
        int idx = 0;
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 9; c++) {
                defaultContentSlots[idx++] = r * 9 + c;
            }
        }
    }


    public static void openContainer(Player player, ContainerManager manager, int page) {
        List<ItemStack> all = manager.getAllItemFromContainer(player);
        int itemsPerPage = defaultContentSlots.length; // 45
        int maxPage = Math.max(1, (int) Math.ceil((double) all.size() / itemsPerPage));
        if (page < 1) page = 1;
        if (page > maxPage) page = maxPage;


        String title = Bukkit.getPluginManager().getPlugin("VContainer").getConfig().getString("title", "§0Container %current-page%/%max-page%");
        title = title.replace("%current-page%", String.valueOf(page)).replace("%max-page%", String.valueOf(maxPage));
        Inventory inv = Bukkit.createInventory(null, SLOTS, VContainer.formatMessage(title));


// Place content
        int startIndex = (page - 1) * itemsPerPage;
        for (int i = 0; i < itemsPerPage; i++) {
            int globalIndex = startIndex + i;
            if (globalIndex >= all.size()) break;
            ItemStack stack = all.get(globalIndex).clone();
            int slot = defaultContentSlots[i];
            inv.setItem(slot, stack);
        }


// Place control buttons on last row
// Prev
        if (page > 1) {
            inv.setItem(CONTROL_ROW_INDEX * 9 + 3, Buttons.buildButton("prev"));
        }
// Next
        if (page < maxPage) {
            inv.setItem(CONTROL_ROW_INDEX * 9 + 5, Buttons.buildButton("next"));
        }


// Fill control row empty with glass panes optionally
        InventoryFill.fillBottomRow(inv);


        player.openInventory(inv);
// Save viewing page to metadata so listener can handle clicks with page
        PlayerViewingCache.setViewing(player, page, maxPage);
    }
}
