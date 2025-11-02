package hu.vzone.vcontainer.gui;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.utils.Buttons;
import hu.vzone.vcontainer.utils.ContainerHolder;
import hu.vzone.vcontainer.utils.InventoryFill;
import hu.vzone.vcontainer.utils.PlayerViewingCache;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class ContainerGUI {

    private static final int ROWS = 6;
    private static final int SLOTS = ROWS * 9; // 54
    private static final int CONTROL_ROW_INDEX = ROWS - 1; // utolsó sor

    // Slotok, ahová az itemek kerülnek
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

        // --- Title betöltése a configból ---
        String title = Bukkit.getPluginManager()
                .getPlugin("VContainer")
                .getConfig()
                .getString("title", "§0Container %current-page%/%max-page%");
        title = title
                .replace("%current-page%", String.valueOf(page))
                .replace("%max-page%", String.valueOf(maxPage));
        title = VContainer.formatMessage(title);

        // --- Itt jön a fő javítás: saját ContainerHolder ---
        Inventory inv = Bukkit.createInventory(new ContainerHolder(player.getName()), SLOTS, title);

        // --- Tartalom elhelyezése ---
        int startIndex = (page - 1) * itemsPerPage;
        for (int i = 0; i < itemsPerPage; i++) {
            int globalIndex = startIndex + i;
            if (globalIndex >= all.size()) break;
            ItemStack stack = all.get(globalIndex).clone();
            int slot = defaultContentSlots[i];
            inv.setItem(slot, stack);
        }

        // --- Navigációs gombok ---
        if (page > 1) {
            inv.setItem(CONTROL_ROW_INDEX * 9 + 3, Buttons.buildButton("prev"));
        }
        if (page < maxPage) {
            inv.setItem(CONTROL_ROW_INDEX * 9 + 5, Buttons.buildButton("next"));
        }

        // --- Alul üres helyek feltöltése üveggel ---
        InventoryFill.fillBottomRow(inv);

        // --- GUI megnyitása ---
        player.openInventory(inv);

        // --- Megnyitott oldal mentése cache-be ---
        PlayerViewingCache.setViewing(player, page, maxPage);
    }

    public static void openContainerForAdmin(Player admin, Player player, ContainerManager manager, int page) {
        List<ItemStack> all = manager.getAllItemFromContainer(player);
        int itemsPerPage = defaultContentSlots.length; // 45
        int maxPage = Math.max(1, (int) Math.ceil((double) all.size() / itemsPerPage));
        if (page < 1) page = 1;
        if (page > maxPage) page = maxPage;

        // --- Title betöltése a configból ---
        String title = Bukkit.getPluginManager()
                .getPlugin("VContainer")
                .getConfig()
                .getString("title", "§0Container %current-page%/%max-page%");
        title = title
                .replace("%current-page%", String.valueOf(page))
                .replace("%max-page%", String.valueOf(maxPage));
        title = VContainer.formatMessage(title);

        // --- Itt jön a fő javítás: saját ContainerHolder ---
        Inventory inv = Bukkit.createInventory(new ContainerHolder(player.getName()), SLOTS, title);

        // --- Tartalom elhelyezése ---
        int startIndex = (page - 1) * itemsPerPage;
        for (int i = 0; i < itemsPerPage; i++) {
            int globalIndex = startIndex + i;
            if (globalIndex >= all.size()) break;
            ItemStack stack = all.get(globalIndex).clone();
            int slot = defaultContentSlots[i];
            inv.setItem(slot, stack);
        }

        // --- Navigációs gombok ---
        if (page > 1) {
            inv.setItem(CONTROL_ROW_INDEX * 9 + 3, Buttons.buildButton("prev"));
        }
        if (page < maxPage) {
            inv.setItem(CONTROL_ROW_INDEX * 9 + 5, Buttons.buildButton("next"));
        }

        // --- Alul üres helyek feltöltése üveggel ---
        InventoryFill.fillBottomRow(inv);

        // --- GUI megnyitása ---
        admin.openInventory(inv);

        // --- Megnyitott oldal mentése cache-be ---
        PlayerViewingCache.setViewing(admin, page, maxPage);
    }
}
