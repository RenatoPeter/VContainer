package hu.vzone.vContainer.listeners;

import hu.vzone.vContainer.gui.ContainerGUI;
import hu.vzone.vContainer.managers.ContainerManager;
import hu.vzone.vContainer.utils.ItemUtils;
import hu.vzone.vContainer.utils.PlayerViewingCache;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class ContainerListener implements Listener {
    private final ContainerManager manager;

    public ContainerListener(ContainerManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (p.getOpenInventory() == null) return;

        String title = p.getOpenInventory().getTitle();
        if (title == null) return;
        if (!ChatColor.stripColor(title).toLowerCase().contains("container")) return;

        e.setCancelled(true);

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null) return;

        PlayerViewingCache.PageInfo info = PlayerViewingCache.getViewing(p);
        int page = (info != null ? info.current : 1);
        int max = (info != null ? info.max : 1);

        int slot = e.getRawSlot();
        int rows = p.getOpenInventory().getTopInventory().getSize() / 9;
        int controlRowStart = (rows - 1) * 9;

        // --- Oldalváltás ---
        if (slot == controlRowStart + 3 && page > 1) {
            ContainerGUI.openContainer(p, manager, page - 1);
            return;
        }
        if (slot == controlRowStart + 5 && page < max) {
            ContainerGUI.openContainer(p, manager, page + 1);
            return;
        }

        // --- Item kivétel a containerből ---
        if (slot < controlRowStart) {
            ItemStack toTake = clicked.clone();
            Inventory inv = p.getInventory();

            int amountToGive = toTake.getAmount();
            int remaining = amountToGive;

            // Stackelés meglévő itemekkel
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack current = inv.getItem(i);
                if (current == null) continue;
                if (ItemUtils.isSameItemWithNBT(current, toTake)) {
                    int maxStack = Math.min(current.getMaxStackSize(), 64); // vagy configból
                    int space = maxStack - current.getAmount();
                    if (space > 0) {
                        int add = Math.min(space, remaining);
                        current.setAmount(current.getAmount() + add);
                        remaining -= add;
                    }
                }
                if (remaining <= 0) break;
            }

            // Ha maradt még és van üres slot
            while (remaining > 0 && inv.firstEmpty() != -1) {
                int add = Math.min(remaining, toTake.getMaxStackSize());
                ItemStack newStack = toTake.clone();
                newStack.setAmount(add);
                inv.addItem(newStack);
                remaining -= add;
            }

            // Amit sikerült odaadni
            int given = amountToGive - remaining;
            if (given > 0) {
                // Csökkentsük a containerben az item mennyiségét
                ItemStack newContainerItem = toTake.clone();
                if (remaining > 0) {
                    newContainerItem.setAmount(remaining);
                    manager.removeItemFromContainer(p, toTake);
                    manager.addItemToContainer(p, newContainerItem);
                } else {
                    manager.removeItemFromContainer(p, toTake);
                }

                p.sendMessage("§aKivettél §f" + given + "§ax " +
                        (toTake.hasItemMeta() && toTake.getItemMeta().hasDisplayName()
                                ? toTake.getItemMeta().getDisplayName()
                                : toTake.getType().name()) + " §a-t a containerből!");
            } else {
                p.sendMessage("§cNem fér már több nálad ebből az itemből!");
            }

            p.updateInventory();
            ContainerGUI.openContainer(p, manager, page);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();
        PlayerViewingCache.remove(p);
    }
}
