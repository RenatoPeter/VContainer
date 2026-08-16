package hu.vzone.vcontainer.gui.search;

import hu.vzone.vcontainer.VContainer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Vanilla search input. PrepareAnvilEvent gives immediate typed-text feedback without packets or NMS. */
public final class ContainerSearchPrompt implements Listener {
    private static final Map<UUID, SearchPrompt> PROMPTS = new ConcurrentHashMap<>();

    public static void open(Player player, String currentQuery, Consumer<String> submit) {
        Inventory inventory = Bukkit.createInventory(player, InventoryType.ANVIL, "VContainer Search");
        ItemStack input = new ItemStack(Material.NAME_TAG);
        ItemMeta meta = input.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(currentQuery == null || currentQuery.isBlank() ? "Search..." : currentQuery);
            input.setItemMeta(meta);
        }
        inventory.setItem(0, input);
        PROMPTS.put(player.getUniqueId(), new SearchPrompt(inventory, submit));
        player.openInventory(inventory);
    }

    public static void clear(UUID playerId) {
        PROMPTS.remove(playerId);
    }

    public static void clearAll() {
        PROMPTS.clear();
    }

    @EventHandler
    public void onPrepare(PrepareAnvilEvent event) {
        SearchPrompt prompt = find(event.getInventory());
        if (prompt == null) return;

        String text = event.getInventory().getRenameText();
        ItemStack result = new ItemStack(Material.COMPASS);
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(text == null || text.isBlank() ? "Show all items" : "Search: " + text);
            result.setItemMeta(meta);
        }
        event.setResult(result);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        SearchPrompt prompt = PROMPTS.get(player.getUniqueId());
        if (prompt == null || event.getInventory() != prompt.inventory || event.getRawSlot() != 2) return;

        event.setCancelled(true);
        String query = ((AnvilInventory) prompt.inventory).getRenameText();
        PROMPTS.remove(player.getUniqueId(), prompt);
        player.closeInventory();
        Bukkit.getScheduler().runTask(VContainer.getInstance(), () -> prompt.submit.accept(query == null ? "" : query));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        SearchPrompt prompt = PROMPTS.get(player.getUniqueId());
        if (prompt != null && prompt.inventory == event.getInventory()) {
            PROMPTS.remove(player.getUniqueId(), prompt);
        }
    }

    private static SearchPrompt find(Inventory inventory) {
        for (SearchPrompt prompt : PROMPTS.values()) {
            if (prompt.inventory == inventory) return prompt;
        }
        return null;
    }

    private record SearchPrompt(Inventory inventory, Consumer<String> submit) {
    }
}
