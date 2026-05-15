package hu.vzone.vcontainer.gui;

import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import dev.triumphteam.gui.guis.PaginatedGui;
import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.managers.StorageBlockManager;
import hu.vzone.vcontainer.managers.StorageBlockManager.StorageBlock;
import hu.vzone.vcontainer.sell.SellService;
import hu.vzone.vcontainer.utils.ConfigItemBuilder;
import hu.vzone.vcontainer.utils.AuditLogger;
import hu.vzone.vcontainer.utils.ItemUtils;
import hu.vzone.vcontainer.utils.PermissionUtils;
import hu.vzone.vcontainer.utils.SkinProvider;
import hu.vzone.vcontainer.utils.StorageBlockItem;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ContainerGUI {

    private static final int ROWS = 6;
    private static final int PAGE_SIZE = 45;
    private static final int PICKUP_SLOT = 45;
    private static final int PREVIOUS_SLOT = 48;
    private static final int SORT_SLOT = 49;
    private static final int NEXT_SLOT = 50;
    private static final int MEMBERS_SLOT = 53;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Map<UUID, SortMode> SORT_MODES = new ConcurrentHashMap<>();
    private static final Map<UUID, OpenContainerView> OPEN_VIEWS = new ConcurrentHashMap<>();
    private static final AtomicLong VIEW_IDS = new AtomicLong();
    private static final java.util.Set<UUID> QUEUED_REFRESHES = ConcurrentHashMap.newKeySet();

    public static void openContainer(Player player, ContainerManager manager, int page) {
        openContainer(player, player.getUniqueId(), player.getName(), manager, page);
    }

    public static void openContainerForAdmin(Player admin, Player owner, ContainerManager manager, int page) {
        openContainer(admin, owner.getUniqueId(), owner.getName(), manager, page);
    }

    public static void openContainerForStorage(Player viewer, UUID ownerId, String ownerName, ContainerManager manager, StorageBlockManager storageBlockManager, String storageKey) {
        openContainer(viewer, ownerId, ownerName, manager, 1, storageBlockManager, storageKey);
    }

    public static void clearSortPreference(UUID playerId) {
        SORT_MODES.remove(playerId);
        OPEN_VIEWS.remove(playerId);
    }

    public static void queueRefresh(UUID ownerId) {
        if (ownerId == null || !QUEUED_REFRESHES.add(ownerId)) return;

        VContainer plugin = VContainer.getInstance();
        Bukkit.getScheduler().runTask(plugin, () -> {
            QUEUED_REFRESHES.remove(ownerId);
            refreshOpenContainers(ownerId);
        });
    }

    private static void openContainer(Player viewer, UUID ownerId, String ownerName, ContainerManager manager, int page) {
        openContainer(viewer, ownerId, ownerName, manager, page, null, null);
    }

    private static void openContainer(Player viewer, UUID ownerId, String ownerName, ContainerManager manager, int page, StorageBlockManager storageBlockManager, String storageKey) {
        VContainer plugin = VContainer.getInstance();
        boolean allowDeposit = plugin.getConfig().getBoolean("container-options.allow-deposit", true);
        boolean allowWithdraw = plugin.getConfig().getBoolean("container-options.allow-withdraw", true);
        boolean depositMessages = plugin.getConfig().getBoolean("container-options.messages.deposit", true);
        boolean withdrawMessages = plugin.getConfig().getBoolean("container-options.messages.withdraw", true);
        boolean sellMessages = plugin.getConfig().getBoolean("container-options.messages.sell", true);
        boolean shiftDepositAll = plugin.getConfig().getBoolean("container-options.shift-transfer.deposit-all", true);
        boolean compactDisplay = plugin.getConfig().getBoolean("container-options.compact-display.enabled", false);
        SellService sellService = plugin.getSellService();
        boolean sellEnabled = sellService != null && sellService.isSellEnabled();

        List<ItemStack> containerItems = manager.getAllItemFromContainer(ownerId);
        List<DisplayEntry> items = getDisplayEntries(containerItems, compactDisplay);
        SortMode sortMode = SORT_MODES.getOrDefault(viewer.getUniqueId(), SortMode.NONE);
        sortEntries(items, sortMode);
        int rows = menuRows(plugin, "container", ROWS);
        int pageSize = menuPageSize(plugin, "container", PAGE_SIZE);
        int maxPage = Math.max(1, (int) Math.ceil((double) items.size() / pageSize));
        int targetPage = Math.max(1, Math.min(page, maxPage));

        PaginatedGui gui = Gui.paginated()
                .title(title(plugin, targetPage, maxPage))
                .rows(rows)
                .pageSize(pageSize)
                .create();

        registerOpenView(gui, viewer, ownerId, ownerName, manager, targetPage, storageBlockManager, storageKey);

        gui.setDefaultClickAction(event -> {
            event.setCancelled(true);
            if (!allowDeposit || !(event.getWhoClicked() instanceof Player player)) return;
            if (event.getClickedInventory() == null || !event.getClickedInventory().equals(player.getInventory())) return;

            if (event.getClick().isShiftClick() && shiftDepositAll) {
                ItemStack clicked = event.getCurrentItem();
                if (clicked == null || clicked.getType().isAir()) return;

                int deposited = depositMatchingInventory(player, ownerId, manager, plugin, clicked);
                if (deposited > 0) {
                    AuditLogger.log("container-deposit-bulk", player, ownerId.toString(), "amount=" + deposited);
                }
                if (deposited > 0 && depositMessages) {
                    sendItemMessage(player, "container.deposit", "{prefix} You put {amount} of {item} into the container.", deposited, getItemName(clicked), ownerName);
                }
                Bukkit.getScheduler().runTask(plugin, () -> openContainer(player, ownerId, ownerName, manager, gui.getCurrentPageNum(), storageBlockManager, storageKey));
                return;
            }

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) return;

            int amount = event.getClick().isRightClick() ? 1 : clicked.getAmount();
            ItemStack toDeposit = clicked.clone();
            toDeposit.setAmount(amount);

            int added = manager.addItemToContainer(ownerId, toDeposit);
            if (added <= 0) {
                send(player, "container.deposit-blocked", "{prefix} This item cannot be stored here.");
                Bukkit.getScheduler().runTask(plugin, () -> openContainer(player, ownerId, ownerName, manager, gui.getCurrentPageNum(), storageBlockManager, storageKey));
                return;
            }
            AuditLogger.log("container-deposit", player, ownerId.toString(), "amount=" + added + " item=" + getItemName(toDeposit));
            if (depositMessages) {
                sendItemMessage(player, "container.deposit", "{prefix} You put {amount} of {item} into the container.", added, getItemName(toDeposit), ownerName);
            }

            if (clicked.getAmount() <= added) {
                event.getClickedInventory().setItem(event.getSlot(), null);
            } else {
                clicked.setAmount(clicked.getAmount() - added);
                event.getClickedInventory().setItem(event.getSlot(), clicked);
            }

            Bukkit.getScheduler().runTask(plugin, () -> openContainer(player, ownerId, ownerName, manager, gui.getCurrentPageNum(), storageBlockManager, storageKey));
        });

        applyStaticItems(gui, plugin, "container");

        addStorageOwnerButtons(gui, plugin, viewer, ownerId, ownerName, manager, storageBlockManager, storageKey);

        gui.setItem(itemSlot(plugin, "container", "sort", SORT_SLOT), ItemBuilder.from(createSortButton(plugin, sortMode)).asGuiItem(event -> {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                SORT_MODES.put(player.getUniqueId(), sortMode.next());
                openContainer(player, ownerId, ownerName, manager, targetPage, storageBlockManager, storageKey);
            }
        }));

        if (targetPage > 1) {
            gui.setItem(itemSlot(plugin, "container", "page-prev", PREVIOUS_SLOT), ItemBuilder.from(createConfiguredButton(plugin, "container", "page-prev")).asGuiItem(event -> {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    openContainer(player, ownerId, ownerName, manager, targetPage - 1, storageBlockManager, storageKey);
                }
            }));
        }

        if (targetPage < maxPage) {
            gui.setItem(itemSlot(plugin, "container", "page-next", NEXT_SLOT), ItemBuilder.from(createConfiguredButton(plugin, "container", "page-next")).asGuiItem(event -> {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    openContainer(player, ownerId, ownerName, manager, targetPage + 1, storageBlockManager, storageKey);
                }
            }));
        }

        for (DisplayEntry entry : items) {
            ItemStack snapshot = createDisplayItem(plugin, viewer, entry, compactDisplay, allowWithdraw, sellEnabled);
            ItemStack target = entry.item().clone();
            target.setAmount(1);

            gui.addItem(ItemBuilder.from(snapshot).asGuiItem(event -> {
                event.setCancelled(true);
                if (!(event.getWhoClicked() instanceof Player player)) return;

                if (sellEnabled && isConfiguredSellClick(plugin, event.getClick())) {
                    handleSellClick(player, manager, ownerId, ownerName, entry, compactDisplay, event.getClick(), gui.getCurrentPageNum(), storageBlockManager, storageKey, sellMessages);
                    return;
                }

                if (!allowWithdraw) return;

                int requested = getWithdrawAmount(plugin, event.getClick(), entry, compactDisplay);
                if (requested <= 0) {
                    return;
                }
                int fit = getFitAmount(player.getInventory(), target, requested);
                if (fit <= 0) {
                    send(player, "container.inventory-full", "{prefix} Your inventory is full.");
                    return;
                }

                int taken = manager.takeItemFromContainer(ownerId, target, Math.min(requested, fit));
                if (taken <= 0) {
                    Bukkit.getScheduler().runTask(plugin, () -> openContainer(player, ownerId, ownerName, manager, gui.getCurrentPageNum(), storageBlockManager, storageKey));
                    return;
                }

                ItemStack toGive = target.clone();
                toGive.setAmount(taken);
                player.getInventory().addItem(toGive);
                AuditLogger.log("container-withdraw", player, ownerId.toString(), "amount=" + taken + " item=" + getItemName(toGive));

                if (withdrawMessages) {
                    sendItemMessage(player, "container.take", "{prefix} You took {amount} of {item} out of the container.", taken, getItemName(toGive), ownerName);
                }

                Bukkit.getScheduler().runTask(plugin, () -> openContainer(player, ownerId, ownerName, manager, gui.getCurrentPageNum(), storageBlockManager, storageKey));
            }));
        }

        gui.open(viewer, targetPage);
    }

    private static void registerOpenView(
            PaginatedGui gui,
            Player viewer,
            UUID ownerId,
            String ownerName,
            ContainerManager manager,
            int page,
            StorageBlockManager storageBlockManager,
            String storageKey
    ) {
        long viewId = VIEW_IDS.incrementAndGet();
        OPEN_VIEWS.put(viewer.getUniqueId(), new OpenContainerView(
                viewId,
                viewer.getUniqueId(),
                ownerId,
                ownerName,
                manager,
                page,
                storageBlockManager,
                storageKey
        ));

        gui.setCloseGuiAction(event -> Bukkit.getScheduler().runTask(VContainer.getInstance(), () -> {
            OpenContainerView current = OPEN_VIEWS.get(viewer.getUniqueId());
            if (current != null && current.viewId() == viewId) {
                OPEN_VIEWS.remove(viewer.getUniqueId());
            }
        }));
    }

    private static void refreshOpenContainers(UUID ownerId) {
        for (OpenContainerView view : new ArrayList<>(OPEN_VIEWS.values())) {
            if (!view.ownerId().equals(ownerId)) continue;

            Player viewer = Bukkit.getPlayer(view.viewerId());
            if (viewer == null || !viewer.isOnline()) {
                OPEN_VIEWS.remove(view.viewerId());
                continue;
            }

            openContainer(
                    viewer,
                    view.ownerId(),
                    view.ownerName(),
                    view.manager(),
                    view.page(),
                    view.storageBlockManager(),
                    view.storageKey()
            );
        }
    }

    private static int getWithdrawAmount(VContainer plugin, ClickType click, DisplayEntry entry, boolean compactDisplay) {
        if (isCompactLineEnabled(plugin, "withdraw-all", "withdraw-all", true)
                && matchesConfiguredAction(plugin, click, "withdraw-all", "withdraw-all")) {
            return compactDisplay ? entry.amount() : entry.item().getAmount();
        }
        if (isCompactLineEnabled(plugin, "withdraw", "withdraw-one", true)
                && matchesConfiguredAction(plugin, click, "withdraw", "withdraw-one")) {
            return 1;
        }
        if (isCompactLineEnabled(plugin, "withdraw-stack", "withdraw-stack", true)
                && matchesConfiguredAction(plugin, click, "withdraw-stack", "withdraw-stack")) {
            return Math.min(entry.item().getMaxStackSize(), compactDisplay ? entry.amount() : entry.item().getAmount());
        }
        return 0;
    }

    private static int depositMatchingInventory(Player player, UUID ownerId, ContainerManager manager, VContainer plugin, ItemStack template) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        int deposited = 0;

        for (int i = 0; i < storage.length; i++) {
            ItemStack item = storage[i];
            if (item == null || item.getType().isAir()) continue;
            if (!ItemUtils.isSameItemWithNBT(item, template)) continue;

            int added = manager.addItemToContainer(ownerId, item.clone());
            if (added <= 0) continue;
            deposited += added;
            if (item.getAmount() <= added) {
                storage[i] = null;
            } else {
                item.setAmount(item.getAmount() - added);
                storage[i] = item;
            }
        }
        inventory.setStorageContents(storage);

        if (plugin.getConfig().getBoolean("container-options.shift-transfer.include-armor", false)) {
            deposited += depositMatchingArmor(inventory, ownerId, manager, template);
        }

        if (plugin.getConfig().getBoolean("container-options.shift-transfer.include-offhand", false)) {
            ItemStack offhand = inventory.getItemInOffHand();
            if (offhand != null && !offhand.getType().isAir() && ItemUtils.isSameItemWithNBT(offhand, template)) {
                int added = manager.addItemToContainer(ownerId, offhand.clone());
                if (added > 0) {
                    deposited += added;
                    if (offhand.getAmount() <= added) {
                        inventory.setItemInOffHand(null);
                    } else {
                        offhand.setAmount(offhand.getAmount() - added);
                        inventory.setItemInOffHand(offhand);
                    }
                }
            }
        }
        return deposited;
    }

    private static int depositMatchingArmor(PlayerInventory inventory, UUID ownerId, ContainerManager manager, ItemStack template) {
        ItemStack[] armor = inventory.getArmorContents();
        int deposited = 0;
        for (int i = 0; i < armor.length; i++) {
            ItemStack item = armor[i];
            if (item == null || item.getType().isAir()) continue;
            if (!ItemUtils.isSameItemWithNBT(item, template)) continue;

            int added = manager.addItemToContainer(ownerId, item.clone());
            if (added <= 0) continue;
            deposited += added;
            if (item.getAmount() <= added) {
                armor[i] = null;
            } else {
                item.setAmount(item.getAmount() - added);
                armor[i] = item;
            }
        }
        inventory.setArmorContents(armor);
        return deposited;
    }

    private static List<DisplayEntry> getDisplayEntries(List<ItemStack> source, boolean compactDisplay) {
        if (!compactDisplay) {
            List<DisplayEntry> entries = new ArrayList<>();
            for (ItemStack item : source) {
                entries.add(new DisplayEntry(item.clone(), item.getAmount()));
            }
            return entries;
        }

        List<DisplayEntry> entries = new ArrayList<>();
        for (ItemStack item : source) {
            DisplayEntry existing = null;
            for (DisplayEntry entry : entries) {
                if (ItemUtils.isSameItemWithNBT(entry.item(), item)) {
                    existing = entry;
                    break;
                }
            }

            if (existing == null) {
                ItemStack template = item.clone();
                template.setAmount(1);
                entries.add(new DisplayEntry(template, item.getAmount()));
            } else {
                entries.set(entries.indexOf(existing), new DisplayEntry(existing.item(), existing.amount() + item.getAmount()));
            }
        }
        return entries;
    }

    private static void sortEntries(List<DisplayEntry> entries, SortMode mode) {
        switch (mode) {
            case ABC_ASC -> entries.sort(Comparator.comparing(ContainerGUI::getSortName, String.CASE_INSENSITIVE_ORDER));
            case ABC_DESC -> entries.sort(Comparator.comparing(ContainerGUI::getSortName, String.CASE_INSENSITIVE_ORDER).reversed());
            case AMOUNT_DESC -> entries.sort(Comparator.comparingInt(DisplayEntry::amount).reversed());
            case AMOUNT_ASC -> entries.sort(Comparator.comparingInt(DisplayEntry::amount));
            case NONE -> {
            }
        }
    }

    private static String getSortName(DisplayEntry entry) {
        ItemStack item = entry.item();
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return ChatColor.stripColor(item.getItemMeta().getDisplayName());
        }
        return item.getType().name();
    }

    private static void addStorageOwnerButtons(
            PaginatedGui gui,
            VContainer plugin,
            Player viewer,
            UUID ownerId,
            String ownerName,
            ContainerManager manager,
            StorageBlockManager storageBlockManager,
            String storageKey
    ) {
        if (storageBlockManager == null || storageKey == null) return;

        StorageBlock storageBlock = storageBlockManager.get(storageKey);
        if (!storageBlockManager.canManage(viewer, storageBlock)) return;

        gui.setItem(itemSlot(plugin, "container", "storage-pickup", PICKUP_SLOT), ItemBuilder.from(createConfiguredButton(plugin, "container", "storage-pickup", viewer)).asGuiItem(event -> {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) return;
            if (!storageBlockManager.canManage(player, storageBlockManager.get(storageKey))) return;

            ConfirmGUI.open(player, "block-pickup", "&0Confirm block pickup", () -> {
                storageBlockManager.removePersonal(storageKey, false);
                AuditLogger.log("personal-block-pickup", player, storageKey, "owner=" + ownerName);
                for (ItemStack leftover : player.getInventory().addItem(StorageBlockItem.build(plugin, 1)).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
                player.closeInventory();
                player.sendMessage(VContainer.formatMessage(player, plugin.getMessageConfig().getString("storage-block.picked-up", "{prefix} Personal storage block picked up.")));
            });
        }));

        gui.setItem(itemSlot(plugin, "container", "storage-members", MEMBERS_SLOT), ItemBuilder.from(createConfiguredButton(plugin, "container", "storage-members", viewer)).asGuiItem(event -> {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                openMembersMenu(player, ownerId, ownerName, manager, storageBlockManager, storageKey);
            }
        }));
    }

    private static void openMembersMenu(Player owner, UUID ownerId, String ownerName, ContainerManager manager, StorageBlockManager storageBlockManager, String storageKey) {
        VContainer plugin = VContainer.getInstance();
        StorageBlock storageBlock = storageBlockManager.get(storageKey);
        if (!storageBlockManager.canManage(owner, storageBlock)) return;

        PaginatedGui gui = Gui.paginated()
                .title(LEGACY.deserialize(VContainer.formatMessage(menu(plugin, "members").getString("title", "&0Storage Members"))))
                .rows(menuRows(plugin, "members", ROWS))
                .pageSize(menuPageSize(plugin, "members", PAGE_SIZE))
                .create();
        gui.setDefaultClickAction(event -> event.setCancelled(true));
        applyStaticItems(gui, plugin, "members");

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(ownerId)) continue;
            gui.addItem(ItemBuilder.from(createMemberButton(plugin, target, storageBlock.members().contains(target.getUniqueId()))).asGuiItem(event -> {
                event.setCancelled(true);
                storageBlockManager.toggleMember(storageKey, target);
                openMembersMenu(owner, ownerId, ownerName, manager, storageBlockManager, storageKey);
            }));
        }

        gui.setItem(itemSlot(plugin, "members", "back", SORT_SLOT), ItemBuilder.from(createConfiguredButton(plugin, "members", "back")).asGuiItem(event -> {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player) {
                openContainer(player, ownerId, ownerName, manager, 1, storageBlockManager, storageKey);
            }
        }));
        gui.open(owner);
    }

    private static ItemStack createMemberButton(VContainer plugin, Player player, boolean member) {
        ConfigurationSection section = itemSection(plugin, "members", "member-toggle", "player");
        String name = "&f{player}";
        List<String> loreRaw = member ? List.of("&aAdded", "&7Click to remove") : List.of("&cNot added", "&7Click to add");
        if (section != null) {
            name = section.getString("Name", section.getString("display_name", section.getString("name", name)));
            loreRaw = member
                    ? getStringList(section, "MemberLore", "member_lore", loreRaw)
                    : getStringList(section, "NotMemberLore", "not_member_lore", loreRaw);
        }

        ItemStack item = section == null
                ? new ItemStack(Material.PLAYER_HEAD)
                : ConfigItemBuilder.build(plugin, section, Material.PLAYER_HEAD, Map.of("player", player.getName(), "uuid", player.getUniqueId().toString()));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            applyHeadOwner(meta, section, player);
            meta.setDisplayName(VContainer.formatMessage(name.replace("{player}", player.getName())));
            List<String> lore = new ArrayList<>();
            for (String line : loreRaw) {
                lore.add(VContainer.formatMessage(line.replace("{player}", player.getName())));
            }
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createSortButton(VContainer plugin, SortMode sortMode) {
        ConfigurationSection section = itemSection(plugin, "container", "sort", "sort");
        String name = "&bSorting: &f{mode}";
        List<String> loreRaw = List.of("&7Click to switch sorting mode", "&7Next: &f{next-mode}");

        if (section != null) {
            name = section.getString("Name", section.getString("display_name", section.getString("name", name)));
            loreRaw = getStringList(section, "Lore", "lore", loreRaw);
        }

        ItemStack item = section == null
                ? new ItemStack(Material.HOPPER)
                : ConfigItemBuilder.build(plugin, section, Material.HOPPER, Map.of(
                "mode", sortMode.displayName(),
                "next-mode", sortMode.next().displayName()
        ));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(formatSortText(name, sortMode));

            List<String> lore = new ArrayList<>();
            for (String line : loreRaw) {
                lore.add(formatSortText(line, sortMode));
            }
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createConfiguredButton(VContainer plugin, String menuName, String action) {
        return createConfiguredButton(plugin, menuName, action, null);
    }

    private static ItemStack createConfiguredButton(VContainer plugin, String menuName, String action, Player player) {
        ConfigurationSection section = itemSection(plugin, menuName, action, legacyButtonPath(action));
        String name = "&cButton";
        List<String> loreRaw = List.of();

        if (section != null) {
            name = section.getString("Name", section.getString("display_name", section.getString("name", name)));
            loreRaw = getStringList(section, "Lore", "lore", loreRaw);
        }

        Map<String, String> placeholders = player == null
                ? Map.of()
                : Map.of("player", player.getName(), "owner", player.getName(), "uuid", player.getUniqueId().toString());
        ItemStack item = section == null
                ? new ItemStack(Material.BARRIER)
                : ConfigItemBuilder.build(plugin, section, Material.BARRIER, placeholders);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            applyHeadOwner(meta, section, player);
            meta.setDisplayName(VContainer.formatMessage(player, replacePlayerPlaceholders(name, player)));
            List<String> lore = new ArrayList<>();
            for (String line : loreRaw) {
                lore.add(VContainer.formatMessage(player, replacePlayerPlaceholders(line, player)));
            }
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static void applyHeadOwner(ItemMeta meta, ConfigurationSection section, Player player) {
        if (!(meta instanceof SkullMeta skullMeta)) return;

        String ownerName = section == null ? "" : getString(section, "HeadOwner", "head_owner", section.getString("skull-owner", ""));
        ownerName = replacePlayerPlaceholders(ownerName, player).trim();
        if (ownerName.isEmpty() && player != null) {
            ownerName = player.getName();
        }
        if (ownerName.isEmpty()) return;

        if (player != null && (ownerName.equalsIgnoreCase(player.getName()) || ownerName.equals(player.getUniqueId().toString()))) {
            SkinProvider.apply(skullMeta, player);
            return;
        }

        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerName);
        skullMeta.setOwningPlayer(owner);
    }

    private static String replacePlayerPlaceholders(String text, Player player) {
        if (text == null || player == null) return text;
        return text
                .replace("{player}", player.getName())
                .replace("{owner}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString());
    }

    private static void applyStaticItems(PaginatedGui gui, VContainer plugin, String menuName) {
        ConfigurationSection items = menu(plugin, menuName).getConfigurationSection("items");
        if (items == null) {
            GuiItem filler = ItemBuilder.from(createFiller()).asGuiItem(event -> event.setCancelled(true));
            for (int slot = 45; slot < 54; slot++) {
                gui.setItem(slot, filler);
            }
            return;
        }

        for (String key : items.getKeys(false)) {
            ConfigurationSection section = items.getConfigurationSection(key);
            if (section == null || !"decoration".equalsIgnoreCase(getString(section, "Action", "action", ""))) continue;

            GuiItem item = ItemBuilder.from(createItem(section, createFiller())).asGuiItem(event -> event.setCancelled(true));
            for (int slot : itemSlots(section)) {
                gui.setItem(slot, item);
            }
        }
    }

    private static ItemStack createItem(ConfigurationSection section, ItemStack fallback) {
        return ConfigItemBuilder.build(VContainer.getInstance(), section, fallback.getType(), Map.of());
    }

    private static ConfigurationSection itemSection(VContainer plugin, String menuName, String action, String legacyPath) {
        ConfigurationSection items = menu(plugin, menuName).getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection section = items.getConfigurationSection(key);
                if (section != null && action.equalsIgnoreCase(getString(section, "Action", "action", ""))) {
                    return section;
                }
            }
        }
        return menu(plugin, menuName).getConfigurationSection("buttons." + legacyPath);
    }

    private static int itemSlot(VContainer plugin, String menuName, String action, int fallback) {
        ConfigurationSection section = itemSection(plugin, menuName, action, legacyButtonPath(action));
        if (section == null) return fallback;

        List<Integer> slots = itemSlots(section);
        return slots.isEmpty() ? fallback : slots.get(0);
    }

    private static List<Integer> itemSlots(ConfigurationSection section) {
        Set<Integer> slots = new LinkedHashSet<>();
        if (section.contains("Slot") || section.contains("slot")) {
            slots.add(parseSlot(section.contains("Slot") ? section.get("Slot") : section.get("slot"), -1));
        }
        List<?> rawSlots = section.contains("Slots") ? section.getList("Slots", List.of()) : section.getList("slots", List.of());
        for (Object raw : rawSlots) {
            addSlotValue(slots, raw);
        }
        slots.remove(-1);
        return new ArrayList<>(slots);
    }

    private static void addSlotValue(Set<Integer> slots, Object raw) {
        if (raw instanceof Number number) {
            slots.add(number.intValue());
            return;
        }

        String value = String.valueOf(raw).trim();
        if (value.contains("-")) {
            String[] parts = value.split("-", 2);
            int from = parseSlot(parts[0], -1);
            int to = parseSlot(parts[1], -1);
            if (from >= 0 && to >= from) {
                for (int slot = from; slot <= to; slot++) {
                    slots.add(slot);
                }
            }
            return;
        }
        slots.add(parseSlot(value, -1));
    }

    private static int parseSlot(Object raw, int fallback) {
        if (raw instanceof Number number) return number.intValue();
        String value = String.valueOf(raw).replace(" ", "");
        if (value.isEmpty()) return fallback;

        int total = 0;
        for (String part : value.split("\\+")) {
            try {
                total += Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return total;
    }

    private static int menuRows(VContainer plugin, String menuName, int fallback) {
        int rows = menu(plugin, menuName).getInt("rows", fallback);
        return Math.max(1, Math.min(6, rows));
    }

    private static int menuPageSize(VContainer plugin, String menuName, int fallback) {
        if (menu(plugin, menuName).contains("page-size")) {
            return Math.max(1, menu(plugin, menuName).getInt("page-size", fallback));
        }

        ConfigurationSection section = itemSection(plugin, menuName, "container-item", "container-item");
        if (section != null) {
            List<Integer> slots = itemSlots(section);
            if (!slots.isEmpty()) return slots.size();
        }
        return fallback;
    }

    private static List<String> getStringList(ConfigurationSection section, String primary, String legacy, List<String> fallback) {
        List<String> values = section.getStringList(primary);
        if (!values.isEmpty()) return values;
        values = section.getStringList(legacy);
        return values.isEmpty() ? fallback : values;
    }

    private static String getString(ConfigurationSection section, String primary, String legacy, String fallback) {
        if (section.contains(primary)) return section.getString(primary, fallback);
        return section.getString(legacy, fallback);
    }

    private static String legacyButtonPath(String action) {
        return switch (action) {
            case "page-prev" -> "prev";
            case "page-next" -> "next";
            case "storage-pickup" -> "pickup";
            case "storage-members" -> "members";
            case "member-toggle" -> "player";
            default -> action;
        };
    }

    private static org.bukkit.configuration.file.FileConfiguration menu(VContainer plugin, String name) {
        org.bukkit.configuration.file.FileConfiguration config = plugin.getMenuConfig(name);
        return config != null ? config : plugin.getConfig();
    }

    private static String formatSortText(String text, SortMode sortMode) {
        return VContainer.formatMessage(text
                .replace("{mode}", sortMode.displayName())
                .replace("{next-mode}", sortMode.next().displayName()));
    }

    private static ItemStack createDisplayItem(VContainer plugin, Player viewer, DisplayEntry entry, boolean compactDisplay, boolean allowWithdraw, boolean sellEnabled) {
        ItemStack item = entry.item().clone();
        if (compactDisplay) {
            item.setAmount(1);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() && meta.getLore() != null
                        ? new ArrayList<>(meta.getLore())
                        : new ArrayList<>();

                lore.addAll(createCompactLore(plugin, viewer, entry, allowWithdraw, sellEnabled));

                meta.setLore(lore);
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    private static List<String> createCompactLore(VContainer plugin, Player viewer, DisplayEntry entry, boolean allowWithdraw, boolean sellEnabled) {
        int amount = entry.amount();
        String amountLine = getCompactLine(plugin, "size", "amount", true, "&7Item stack size: &f{amount}")
                .replace("{amount}", String.valueOf(amount));
        if (!isCompactLineEnabled(plugin, "size", "amount", true)) amountLine = "";

        String withdrawAllLine = allowWithdraw && isCompactLineEnabled(plugin, "withdraw-all", "withdraw-all", true)
                ? getCompactLine(plugin, "withdraw-all", "withdraw-all", true, "&eLeft click to withdraw all")
                : "";
        String withdrawOneLine = allowWithdraw && isCompactLineEnabled(plugin, "withdraw", "withdraw-one", true)
                ? getCompactLine(plugin, "withdraw", "withdraw-one", true, "&eRight click to withdraw 1")
                : "";
        String withdrawStackLine = allowWithdraw && isCompactLineEnabled(plugin, "withdraw-stack", "withdraw-stack", true)
                ? getCompactLine(plugin, "withdraw-stack", "withdraw-stack", true, "&eMiddle click to withdraw 1 stack")
                : "";

        SellPreview preview = createSellPreview(plugin, viewer, entry, sellEnabled);
        boolean showSellFormat = preview.showLore();
        String sellAllLine = showSellFormat && isCompactLineEnabled(plugin, "sell-all", "sell-all", true)
                ? getCompactLine(plugin, "sell-all", "sell-all", true, "&eShift + Left click to sell all &8(&6%price-all%&8)")
                .replace("%price-all%", preview.allPrice())
                : "";
        String sellOneLine = showSellFormat && isCompactLineEnabled(plugin, "sell", "sell", true)
                ? getCompactLine(plugin, "sell", "sell", true, "&eShift + Right click to sell 1 &8(&6%price-one%&8)")
                .replace("%price-one%", preview.onePrice())
                : "";
        String sellStackLine = showSellFormat && isCompactLineEnabled(plugin, "sell-stack", "sell-stack", true)
                ? getCompactLine(plugin, "sell-stack", "sell-stack", true, "&eCtrl + Q to sell 1 stack &8(&6%price-stack%&8)")
                .replace("%price-stack%", preview.stackPrice())
                : "";

        List<String> sellFormat = plugin.getConfig().getStringList("container-options.compact-display.sell-format");
        if (sellFormat.isEmpty()) {
            sellFormat = List.of("", "%sell-all-line%", "%sell-one-line%", "%sell-stack-line%", "");
        }
        List<String> resolvedSellFormat = new ArrayList<>();
        if (showSellFormat) {
            for (String line : sellFormat) {
                String formatted = line
                        .replace("%sell-all-line%", sellAllLine)
                        .replace("%sell-one-line%", sellOneLine)
                        .replace("%sell-stack-line%", sellStackLine);
                if (!formatted.isEmpty() || line.isEmpty()) {
                    resolvedSellFormat.add(VContainer.formatMessage(formatted));
                }
            }
        }

        List<String> format = plugin.getConfig().getStringList("container-options.compact-display.format");
        if (format.isEmpty()) {
            format = new ArrayList<>();
            format.add("%amount-line%");
            if (allowWithdraw) {
                format.add("%withdraw-all-line%");
                format.add("%withdraw-one-line%");
                format.add("%withdraw-stack-line%");
            }
        }

        List<String> lore = new ArrayList<>();
        for (String line : format) {
            String formatted = line
                    .replace("%amount-line%", amountLine)
                    .replace("%withdraw-all-line%", withdrawAllLine)
                    .replace("%withdraw-one-line%", withdrawOneLine)
                    .replace("%withdraw-stack-line%", withdrawStackLine)
                    .replace("%sell-format%", String.join("\n", resolvedSellFormat));
            if (formatted.contains("\n")) {
                boolean preserveBlank = line.isEmpty() || line.contains("%sell-format%");
                for (String split : formatted.split("\n", -1)) {
                    if (!split.isEmpty() || preserveBlank) {
                        lore.add(VContainer.formatMessage(split));
                    }
                }
                continue;
            }
            if (!formatted.isEmpty() || line.isEmpty()) {
                lore.add(VContainer.formatMessage(formatted));
            }
        }
        return lore;
    }

    private static boolean isCompactLineEnabled(VContainer plugin, String section, String legacyPrefix, boolean fallback) {
        String base = "container-options.compact-display.";
        if (plugin.getConfig().contains(base + section + ".enable")) {
            return plugin.getConfig().getBoolean(base + section + ".enable", fallback);
        }
        if (plugin.getConfig().contains(base + section + ".enabled")) {
            return plugin.getConfig().getBoolean(base + section + ".enabled", fallback);
        }
        return plugin.getConfig().getBoolean(base + legacyPrefix + "-line-enabled", fallback);
    }

    private static String getCompactLine(VContainer plugin, String section, String legacyPrefix, boolean fallbackEnabled, String fallbackLine) {
        String base = "container-options.compact-display.";
        if (!isCompactLineEnabled(plugin, section, legacyPrefix, fallbackEnabled)) return "";
        String nested = plugin.getConfig().getString(base + section + ".line");
        if (nested != null) return nested;
        return plugin.getConfig().getString(base + legacyPrefix + "-line", fallbackLine);
    }

    private static Component title(VContainer plugin, int page, int maxPage) {
        String title = menu(plugin, "container").getString("title", "&0Container %current-page%/%max-page%");
        title = title.replace("%current-page%", String.valueOf(page)).replace("%max-page%", String.valueOf(maxPage));
        return LEGACY.deserialize(VContainer.formatMessage(title));
    }

    private static ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private static int getFitAmount(Inventory inventory, ItemStack item, int requestedAmount) {
        int fit = 0;
        int maxStackSize = item.getMaxStackSize();

        for (ItemStack current : inventory.getStorageContents()) {
            if (current == null || current.getType().isAir()) {
                fit += maxStackSize;
            } else if (current.isSimilar(item)) {
                fit += Math.max(0, Math.min(maxStackSize, current.getMaxStackSize()) - current.getAmount());
            }

            if (fit >= requestedAmount) return requestedAmount;
        }

        return fit;
    }

    private static void send(Player player, String path, String fallback) {
        VContainer plugin = VContainer.getInstance();
        player.sendMessage(VContainer.formatMessage(player, plugin.getMessageConfig().getString(path, fallback)));
    }

    private static void sendItemMessage(Player player, String path, String fallback, int amount, String itemName, String ownerName) {
        if (!PermissionUtils.has(player, "vcontainer.notify")) return;

        VContainer plugin = VContainer.getInstance();
        String message = plugin.getMessageConfig().getString(path, fallback);
        player.sendMessage(VContainer.formatMessage(player, message
                .replace("{amount}", String.valueOf(amount))
                .replace("{item}", itemName)
                .replace("{player}", ownerName)));
    }

    private static void sendSellMessage(Player player, int amount, String itemName, String ownerName, String price) {
        if (!PermissionUtils.has(player, "vcontainer.notify")) return;

        VContainer plugin = VContainer.getInstance();
        String message = plugin.getMessageConfig().getString("container.sell", "{prefix} You sold {amount} of {item} for {price}.");
        player.sendMessage(VContainer.formatMessage(player, message
                .replace("{amount}", String.valueOf(amount))
                .replace("{item}", itemName)
                .replace("{player}", ownerName)
                .replace("{price}", price)));
    }

    private static int getSellAmount(VContainer plugin, ClickType click, DisplayEntry entry, boolean compactDisplay) {
        if (isCompactLineEnabled(plugin, "sell", "sell", true)
                && matchesConfiguredAction(plugin, click, "sell", "sell")) {
            return 1;
        }
        if (isCompactLineEnabled(plugin, "sell-stack", "sell-stack", true)
                && matchesConfiguredAction(plugin, click, "sell-stack", "sell-stack")) {
            return Math.min(entry.item().getMaxStackSize(), compactDisplay ? entry.amount() : entry.item().getAmount());
        }
        if (isCompactLineEnabled(plugin, "sell-all", "sell-all", true)
                && matchesConfiguredAction(plugin, click, "sell-all", "sell-all")) {
            return compactDisplay ? entry.amount() : entry.item().getAmount();
        }
        return 0;
    }

    private static void handleSellClick(
            Player player,
            ContainerManager manager,
            UUID ownerId,
            String ownerName,
            DisplayEntry entry,
            boolean compactDisplay,
            ClickType click,
            int page,
            StorageBlockManager storageBlockManager,
            String storageKey,
            boolean sellMessages
    ) {
        VContainer plugin = VContainer.getInstance();
        SellService sellService = plugin.getSellService();
        if (sellService == null || !sellService.isSellEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> openContainer(player, ownerId, ownerName, manager, page, storageBlockManager, storageKey));
            return;
        }

        int requested = getSellAmount(plugin, click, entry, compactDisplay);
        if (requested <= 0) {
            Bukkit.getScheduler().runTask(plugin, () -> openContainer(player, ownerId, ownerName, manager, page, storageBlockManager, storageKey));
            return;
        }
        SellService.SellResult result = sellService.sell(player, manager, ownerId, entry.item(), requested);
        if (!result.success()) {
            String path = result.reason() == SellService.UnavailableReason.NO_PRICE
                    ? "container.sell-unavailable"
                    : "container.sell-provider-unavailable";
            String fallback = result.reason() == SellService.UnavailableReason.NO_PRICE
                    ? "{prefix} This item cannot be sold."
                    : "{prefix} The sell system is not available right now.";
            send(player, path, fallback);
            Bukkit.getScheduler().runTask(plugin, () -> openContainer(player, ownerId, ownerName, manager, page, storageBlockManager, storageKey));
            return;
        }

        ItemStack soldItem = entry.item().clone();
        soldItem.setAmount(result.amount());
        AuditLogger.log("container-sell", player, ownerId.toString(), "amount=" + result.amount() + " item=" + getItemName(soldItem) + " price=" + result.totalPrice());
        if (sellMessages) {
            sendSellMessage(player, result.amount(), getItemName(soldItem), ownerName, result.formattedPrice());
        }

        Bukkit.getScheduler().runTask(plugin, () -> openContainer(player, ownerId, ownerName, manager, page, storageBlockManager, storageKey));
    }

    private static SellPreview createSellPreview(VContainer plugin, Player viewer, DisplayEntry entry, boolean sellEnabled) {
        if (!sellEnabled || viewer == null) {
            return SellPreview.hidden();
        }

        SellService sellService = plugin.getSellService();
        if (sellService == null || !sellService.isSellEnabled()) {
            return SellPreview.hidden();
        }

        int allAmount = entry.amount();
        int stackAmount = Math.min(entry.item().getMaxStackSize(), allAmount);
        SellService.SellQuote allQuote = sellService.quote(viewer, entry.item(), allAmount);
        SellService.SellQuote oneQuote = sellService.quote(viewer, entry.item(), 1);
        SellService.SellQuote stackQuote = sellService.quote(viewer, entry.item(), stackAmount);
        boolean showWithoutPrice = plugin.getConfig().getBoolean("container-options.compact-display.show-sell-format-without-price", false);
        boolean anySellable = allQuote.sellable() || oneQuote.sellable() || stackQuote.sellable();
        if (!anySellable && !showWithoutPrice) {
            return SellPreview.hidden();
        }

        String unavailable = sellService.unavailablePricePlaceholder();
        return new SellPreview(
                true,
                allQuote.sellable() ? allQuote.formattedPrice() : unavailable,
                oneQuote.sellable() ? oneQuote.formattedPrice() : unavailable,
                stackQuote.sellable() ? stackQuote.formattedPrice() : unavailable
        );
    }

    private static boolean matchesConfiguredAction(VContainer plugin, ClickType actualClick, String section, String legacyPrefix) {
        return configuredAction(plugin, section, legacyPrefix) == GuiClickAction.fromClickType(actualClick);
    }

    private static boolean isConfiguredSellClick(VContainer plugin, ClickType actualClick) {
        return isCompactLineEnabled(plugin, "sell-all", "sell-all", true)
                && matchesConfiguredAction(plugin, actualClick, "sell-all", "sell-all")
                || isCompactLineEnabled(plugin, "sell", "sell", true)
                && matchesConfiguredAction(plugin, actualClick, "sell", "sell")
                || isCompactLineEnabled(plugin, "sell-stack", "sell-stack", true)
                && matchesConfiguredAction(plugin, actualClick, "sell-stack", "sell-stack");
    }

    private static GuiClickAction configuredAction(VContainer plugin, String section, String legacyPrefix) {
        String base = "container-options.compact-display.";
        String configured = plugin.getConfig().getString(base + section + ".action");
        if (configured != null && !configured.isBlank()) {
            return GuiClickAction.fromConfig(configured);
        }
        return switch (section) {
            case "withdraw-all" -> GuiClickAction.LEFT_CLICK;
            case "withdraw" -> GuiClickAction.RIGHT_CLICK;
            case "withdraw-stack" -> GuiClickAction.MIDDLE_CLICK;
            case "sell-all" -> GuiClickAction.SHIFT_LEFT_CLICK;
            case "sell" -> GuiClickAction.SHIFT_RIGHT_CLICK;
            case "sell-stack" -> GuiClickAction.ITEM_DROP;
            default -> GuiClickAction.NONE;
        };
    }

    private static String getItemName(ItemStack item) {
        return item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                ? item.getItemMeta().getDisplayName()
                : formatMaterialName(item.getType());
    }

    private static String formatMaterialName(Material material) {
        return switch (material) {
            case REDSTONE -> "Redstone Dust";
            default -> {
                String[] parts = material.name().toLowerCase().split("_");
                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    if (parts[i].isEmpty()) continue;
                    if (builder.length() > 0) builder.append(' ');
                    builder.append(Character.toUpperCase(parts[i].charAt(0)));
                    if (parts[i].length() > 1) {
                        builder.append(parts[i].substring(1));
                    }
                }
                yield builder.toString();
            }
        };
    }

    private enum SortMode {
        NONE("None"),
        ABC_ASC("ABC A-Z"),
        ABC_DESC("ABC Z-A"),
        AMOUNT_DESC("Most items"),
        AMOUNT_ASC("Fewest items");

        private final String displayName;

        SortMode(String displayName) {
            this.displayName = displayName;
        }

        private String displayName() {
            VContainer plugin = VContainer.getInstance();
            if (plugin == null) return displayName;
            return plugin.getMessageConfig().getString("sort-types." + name(), displayName);
        }

        private SortMode next() {
            SortMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private record DisplayEntry(ItemStack item, int amount) {
    }

    private record OpenContainerView(
            long viewId,
            UUID viewerId,
            UUID ownerId,
            String ownerName,
            ContainerManager manager,
            int page,
            StorageBlockManager storageBlockManager,
            String storageKey
    ) {
    }

    private record SellPreview(boolean showLore, String allPrice, String onePrice, String stackPrice) {

        private static SellPreview hidden() {
            return new SellPreview(false, "", "", "");
        }
    }

    private enum GuiClickAction {
        NONE,
        RIGHT_CLICK,
        LEFT_CLICK,
        MIDDLE_CLICK,
        SHIFT_RIGHT_CLICK,
        SHIFT_LEFT_CLICK,
        ITEM_DROP,
        HAND_SWAP;

        private static GuiClickAction fromConfig(String raw) {
            if (raw == null) {
                return NONE;
            }
            String normalized = raw
                    .trim()
                    .toUpperCase()
                    .replace('_', ' ')
                    .replace('-', ' ')
                    .replaceAll("\\s+", " ");
            return switch (normalized) {
                case "RIGHT CLICK" -> RIGHT_CLICK;
                case "LEFT CLICK" -> LEFT_CLICK;
                case "MIDDLE CLICK" -> MIDDLE_CLICK;
                case "SHIFT + RIGHT CLICK", "SHIFT RIGHT CLICK" -> SHIFT_RIGHT_CLICK;
                case "SHIFT + LEFT CLICK", "SHIFT LEFT CLICK" -> SHIFT_LEFT_CLICK;
                case "ITEM DROP", "DROP" -> ITEM_DROP;
                case "HAND SWAP", "SWAP OFFHAND", "OFFHAND SWAP" -> HAND_SWAP;
                default -> NONE;
            };
        }

        private static GuiClickAction fromClickType(ClickType clickType) {
            return switch (clickType) {
                case RIGHT -> RIGHT_CLICK;
                case LEFT -> LEFT_CLICK;
                case MIDDLE -> MIDDLE_CLICK;
                case SHIFT_RIGHT -> SHIFT_RIGHT_CLICK;
                case SHIFT_LEFT -> SHIFT_LEFT_CLICK;
                case DROP -> ITEM_DROP;
                case SWAP_OFFHAND -> HAND_SWAP;
                default -> NONE;
            };
        }
    }
}
