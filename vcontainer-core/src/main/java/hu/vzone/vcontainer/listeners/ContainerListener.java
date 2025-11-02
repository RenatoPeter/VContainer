package hu.vzone.vcontainer.listeners;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.gui.ContainerGUI;
import hu.vzone.vcontainer.utils.ContainerHolder;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.utils.ItemUtils;
import hu.vzone.vcontainer.utils.PlayerViewingCache;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class ContainerListener implements Listener {

    private final ContainerManager manager;
    private final VContainer plugin;

    public ContainerListener(ContainerManager manager, VContainer plugin) {
        this.manager = manager;
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();

        Inventory topInv = e.getView().getTopInventory();
        if (!(topInv.getHolder() instanceof ContainerHolder)) return; // ✅ csak a VContainer GUI-ra reagál

        e.setCancelled(true);

        ItemStack clicked = e.getCurrentItem();
        if (clicked == null) return;

        PlayerViewingCache.PageInfo info = PlayerViewingCache.getViewing(p);
        int page = (info != null ? info.current : 1);
        int max = (info != null ? info.max : 1);

        int slot = e.getRawSlot();
        int rows = topInv.getSize() / 9;
        int controlRowStart = (rows - 1) * 9;

        // --- Oldalváltás ---
        if (slot == controlRowStart + 3 && page > 1) {
            if (topInv.getHolder() instanceof ContainerHolder holder) {
                String ownerName = holder.getOwner(); // ContainerHolder-ben tárolt tulajdonos neve
                Player owner = Bukkit.getPlayerExact(ownerName);
                if (owner != null && !owner.equals(p)) {
                    // Admin nézi más containerét
                    ContainerGUI.openContainerForAdmin(p, owner, manager, page - 1);
                } else {
                    // Sajátját nézi
                    ContainerGUI.openContainer(p, manager, page - 1);
                }
            }
            return;
        }

        if (slot == controlRowStart + 5 && page < max) {
            if (topInv.getHolder() instanceof ContainerHolder holder) {
                String ownerName = holder.getOwner();
                Player owner = Bukkit.getPlayerExact(ownerName);
                if (owner != null && !owner.equals(p)) {
                    ContainerGUI.openContainerForAdmin(p, owner, manager, page + 1);
                } else {
                    ContainerGUI.openContainer(p, manager, page + 1);
                }
            }
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
                    int maxStack = Math.min(current.getMaxStackSize(), 64);
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
                ItemStack newContainerItem = toTake.clone();
                if (remaining > 0) {
                    newContainerItem.setAmount(remaining);
                    manager.removeItemFromContainer(p, toTake);
                    manager.addItemToContainer(p, newContainerItem);
                } else {
                    manager.removeItemFromContainer(p, toTake);
                }

                String take = plugin.getMessageConfig().getString(
                        "container.take",
                        "{prefix} You took {amount} of {item} out of the container."
                );

                if(p.hasPermission("vcontainer.notify")){
                    p.sendMessage(plugin.formatMessage(
                            take.replace("{amount}", String.valueOf(given))
                                    .replace("{item}", (toTake.hasItemMeta() && toTake.getItemMeta().hasDisplayName()
                                            ? toTake.getItemMeta().getDisplayName()
                                            : toTake.getType().name()))
                    ));
                }

            } else {
                String inventoryFull = plugin.getMessageConfig().getString(
                        "container.inventory-full",
                        "{prefix} Your inventory is full."
                );
                p.sendMessage(plugin.formatMessage(inventoryFull));
            }

            p.updateInventory();
            ContainerGUI.openContainer(p, manager, page);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        Player p = (Player) e.getPlayer();

        Inventory topInv = e.getView().getTopInventory();
        if (!(topInv.getHolder() instanceof ContainerHolder)) return; // ✅ csak a container GUI-nál
        PlayerViewingCache.remove(p);
    }
}
