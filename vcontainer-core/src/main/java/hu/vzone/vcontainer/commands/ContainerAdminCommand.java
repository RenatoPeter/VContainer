package hu.vzone.vcontainer.commands;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.gui.ConfirmGUI;
import hu.vzone.vcontainer.gui.ContainerGUI;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.managers.StorageBlockManager;
import hu.vzone.vcontainer.storage.StorageSettings;
import hu.vzone.vcontainer.utils.AdminDataService;
import hu.vzone.vcontainer.utils.PermissionUtils;
import hu.vzone.vcontainer.utils.StorageBlockItem;
import hu.vzone.vcontainer.utils.AuditLogger;
import dev.lone.itemsadder.api.CustomStack;
import io.lumine.mythic.api.adapters.AbstractItemStack;
import io.lumine.mythic.bukkit.BukkitAdapter;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.items.MythicItem;
import io.th0rgal.oraxen.api.OraxenItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Location;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

public class ContainerAdminCommand implements CommandExecutor, TabCompleter {

    private final VContainer plugin;
    private final ContainerManager manager;
    private final StorageBlockManager storageBlockManager;
    private final boolean hasOraxen;
    private final boolean hasMythicMobs;
    private final boolean hasItemsAdder;

    public ContainerAdminCommand(VContainer plugin, ContainerManager manager, StorageBlockManager storageBlockManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.storageBlockManager = storageBlockManager;
        this.hasOraxen = Bukkit.getPluginManager().isPluginEnabled("Oraxen");
        this.hasMythicMobs = Bukkit.getPluginManager().isPluginEnabled("MythicMobs");
        this.hasItemsAdder = Bukkit.getPluginManager().isPluginEnabled("ItemsAdder");
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 && !PermissionUtils.has(sender, "vcontainer.admin")) {
            sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.no-permission", "{prefix} You don't have any permission!")));
            return true;
        }

        if (args.length == 0) {
            List<String> helps = plugin.getMessageConfig().getStringList("admin-command.usage");
            for (String help : helps){
                sender.sendMessage(plugin.formatMessage(help));
            }
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        if (plugin.isRestartRequired() && !action.equals("backup") && !action.equals("reload")) {
            sender.sendMessage(plugin.formatMessage("{prefix} VContainer is waiting for a server restart. " + plugin.getRestartReason()));
            return true;
        }

        switch (action) {
            case "reload":
                if (!require(sender, "vcontainer.admin.reload")) return true;
                plugin.reloadMainConfig();
                plugin.reloadMessageConfig();
                plugin.reloadDatabaseConfig();
                plugin.reloadMigrationConfig();
                plugin.reloadPricesConfig();
                plugin.reloadMenuConfigs();
                storageBlockManager.reload();
                plugin.getUpdateChecker().checkAsync();
                sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.reload", "{prefix} Plugin successfully reloaded!")));
                return true;

            case "set":
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("command.only-players-can-use", "{prefix} Only players can use this command!")));
                    return true;
                }
                if (!PermissionUtils.has(sender, "vcontainer.admin.set")) {
                    sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.no-permission", "{prefix} You don't have any permission!")));
                    return true;
                }

                int distance = plugin.getConfig().getInt("storage-block.set-target-distance", 6);
                Block targetBlock = player.getTargetBlockExact(distance);
                if (targetBlock == null || targetBlock.getType().isAir()) {
                    sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("storage-block.no-target", "{prefix} Look at a block first.")));
                    return true;
                }

                if (!storageBlockManager.add(targetBlock)) {
                    sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("storage-block.already-set", "{prefix} This block is already a storage block.")));
                    return true;
                }

                AuditLogger.log("global-block-set", sender, storageBlockManager.key(targetBlock), "material=" + targetBlock.getType());
                sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("storage-block.set", "{prefix} Storage block created.")));
                return true;

            case "give-block": {
                if (!require(sender, "vcontainer.admin.give")) return true;
                GiveBlockArgs parsed = parseGiveBlockArgs(sender, args);
                if (parsed == null) return true;

                ItemStack item = StorageBlockItem.build(plugin, parsed.amount());
                parsed.receiver().getInventory().addItem(item);

                if (!parsed.silent()) {
                    parsed.receiver().sendMessage(VContainer.formatMessage(plugin.getMessageConfig().getString(
                            "storage-block.received",
                            "{prefix} You received {amount} storage block item(s)."
                    ).replace("{amount}", String.valueOf(parsed.amount()))));
                }

                sender.sendMessage(VContainer.formatMessage(plugin.getMessageConfig().getString(
                        "admin-command.give-block",
                        "{prefix} You gave {amount} storage block item(s) to {player}."
                ).replace("{amount}", String.valueOf(parsed.amount())).replace("{player}", parsed.receiver().getName())));
                AuditLogger.log("give-personal-block-item", sender, parsed.receiver().getUniqueId().toString(), "amount=" + parsed.amount() + " silent=" + parsed.silent());
                return true;
            }

            case "blocks":
                return handleBlocks(sender, args);

            case "backup":
                if (!require(sender, "vcontainer.admin.backup")) return true;
                return handleBackup(sender, args);

            case "migrate":
                if (!require(sender, "vcontainer.admin.migrate")) return true;
                return handleMigrate(sender, args);

            case "repair":
                if (!require(sender, "vcontainer.admin.repair")) return true;
                return handleRepair(sender, args);

            case "open":
            case "clear":
                if (args.length < 2) {
                    List<String> helps = plugin.getMessageConfig().getStringList("admin-command.usage");
                    for (String help : helps){
                        sender.sendMessage(plugin.formatMessage(help));
                    }
                    return true;
                }

                OfflinePlayer target = offlinePlayer(args[1]);
                if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
                    sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.player-not-found", "{prefix} The specific player not found! &8(&7{player}&8)").replace("{player}", args[1])));
                    return true;
                }

//                opened: "{prefix} You opened {player}'s container"
                if (action.equals("open")) {
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("command.only-players-can-use", "{prefix} Only players can use this command!")));
                        return true;
                    }
                    if (!(target instanceof Player onlineTarget)) {
                        sender.sendMessage(plugin.formatMessage("{prefix} This player is offline."));
                        return true;
                    }
                    Player admin = (Player) sender;
                    ContainerGUI.openContainerForAdmin(admin, onlineTarget, manager, 1);
                    sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.open", "{prefix} You opened {player}'s container!").replace("{player}", target.getName())));
                } else {
                    if (!require(sender, "vcontainer.admin.clear")) return true;
                    Runnable clearAction = () -> {
                        manager.clearContainer(target.getUniqueId());
                        AuditLogger.log("container-clear", sender, target.getUniqueId().toString(), "player=" + target.getName());
                        sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.clear", "{prefix} You cleared {player}'s container!").replace("{player}", target.getName())));
                    };
                    if (sender instanceof Player player) {
                        ConfirmGUI.open(player, "&0Confirm container clear", clearAction);
                    } else {
                        clearAction.run();
                    }
                }
                return true;

            case "give":
                if (!require(sender, "vcontainer.admin.give")) return true;
                if (args.length < 3) {
                    List<String> helps = plugin.getMessageConfig().getStringList("admin-command.usage");
                    for (String help : helps){
                        sender.sendMessage(plugin.formatMessage(help));
                    }
                    return true;
                }

                String source = args[1].toLowerCase(Locale.ROOT);
                String itemName = args[2];
                OfflinePlayer receiver = null;
                int amount = 1;

                if (args.length >= 4)
                    receiver = offlinePlayer(args[3]);
                if (args.length >= 5) {
                    try {
                        amount = Integer.parseInt(args[4]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.invalid-amount", "{prefix} Invalid amount: {amount}").replace("{amount}", args[4])));
                        return true;
                    }
                }
                if (amount <= 0) {
                    sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.invalid-amount", "{prefix} Invalid amount: {amount}").replace("{amount}", String.valueOf(amount))));
                    return true;
                }

                if (receiver == null && sender instanceof Player player)
                    receiver = player;

                if (receiver == null) {
                    sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.need-a-player", "{prefix} Please enter a player!")));
                    return true;
                }

                ItemStack item = null;

                switch (source) {
                    case "minecraft" -> {
                        Material mat = Material.matchMaterial(itemName);
                        if (mat != null)
                            item = new ItemStack(mat, amount);
                    }

                    case "oraxen" -> {
                        if (hasOraxen && OraxenItems.exists(itemName)) {
                            var builder = OraxenItems.getItemById(itemName);
                            if (builder != null) {
                                item = builder.build();
                            }
                            if (item != null) {
                                item.setAmount(amount);
                            }
                        }
                    }

                    case "mythicmobs" -> {
                        if (hasMythicMobs) {
                            MythicBukkit mythicBukkit = MythicBukkit.inst();
                            if (mythicBukkit == null || mythicBukkit.getItemManager() == null) break;

                            for (MythicItem mythicItem : mythicBukkit.getItemManager().getItems()) {
                                if (mythicItem.getInternalName().equalsIgnoreCase(itemName)) {
                                    AbstractItemStack absItem = mythicItem.generateItemStack(1);
                                    if (absItem != null) {
                                        ItemStack bukkitItem = BukkitAdapter.adapt(absItem);
                                        item = bukkitItem;
                                        item.setAmount(amount);
                                    }
                                    break;
                                }
                            }
                        }
                    }

                    case "itemsadder" -> {
                        if (hasItemsAdder && CustomStack.isInRegistry(itemName)) {
                            CustomStack customStack = CustomStack.getInstance(itemName);
                            if (customStack != null) {
                                item = customStack.getItemStack();
                                item.setAmount(amount);
                            }
                        }
                    }


                    default -> {
                        sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.unknown-source", "{prefix} Unknown source: {source}").replace("{source}", source)));
                        return true;
                    }
                }

                if (item == null) {
                    sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.item-not-found", "{prefix} The specified item was not found. &8(&7{item}&8)").replace("{item}", itemName)));
                    return true;
                }

                int added = manager.addItemToContainer(receiver.getUniqueId(), item);
                if (added <= 0) {
                    sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("container.deposit-blocked", "{prefix} This item cannot be stored here.")));
                    return true;
                }
                AuditLogger.log("admin-add-item", sender, receiver.getUniqueId().toString(), "source=" + source + " item=" + itemName + " amount=" + added);
                sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.add-to-container", "{prefix} {amount} of {item} items added to {player}'s container.")
                        .replace("{amount}", String.valueOf(added))
                        .replace("{item}", itemName)
                        .replace("{player}", playerName(receiver))));
                return true;
            default:
                sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.unknown-action", "{prefix} Unknown action: {action}")
                        .replace("{action}", action)));
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!PermissionUtils.has(sender, "vcontainer.admin")) return Collections.emptyList();
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            for (String s : List.of("open", "clear", "reload", "give", "give-block", "set", "blocks", "backup", "migrate", "repair")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    completions.add(s);
            }
            return completions;
        }

        if (args[0].equalsIgnoreCase("give")) {
            if (args.length == 2) {
                if ("minecraft".startsWith(args[1].toLowerCase(Locale.ROOT))) completions.add("minecraft");
                if (hasOraxen && "oraxen".startsWith(args[1].toLowerCase(Locale.ROOT))) completions.add("oraxen");
                if (hasMythicMobs && "mythicmobs".startsWith(args[1].toLowerCase(Locale.ROOT))) completions.add("mythicmobs");
                if (hasItemsAdder && "itemsadder".startsWith(args[1].toLowerCase(Locale.ROOT))) completions.add("itemsadder");
                return completions;
            }

            if (args.length == 3) {
                String src = args[1].toLowerCase(Locale.ROOT);
                String partial = args[2].toLowerCase(Locale.ROOT);

                switch (src) {
                    case "minecraft" -> completions.addAll(
                            Arrays.stream(Material.values())
                                    .map(mat -> mat.name().toLowerCase(Locale.ROOT))
                                    .filter(name -> name.startsWith(partial))
                                    .limit(50)
                                    .collect(Collectors.toList())
                    );

                    case "oraxen" -> {
                        if (hasOraxen) {
                            completions.addAll(OraxenItems.getEntries().stream()
                                    .map(entry -> entry.getKey().toLowerCase(Locale.ROOT))
                                    .filter(id -> id.startsWith(partial))
                                    .collect(Collectors.toList()));
                        }
                    }

                    case "mythicmobs" -> {
                        if (hasMythicMobs && MythicBukkit.inst() != null && MythicBukkit.inst().getItemManager() != null) {
                            completions.addAll(MythicBukkit.inst().getItemManager().getItems().stream()
                                    .map(MythicItem::getInternalName)
                                    .map(name -> name.toLowerCase(Locale.ROOT))
                                    .filter(name -> name.startsWith(partial))
                                    .collect(Collectors.toList()));
                        }
                    }

                    case "itemsadder" -> {
                        if (hasItemsAdder) {
                            completions.addAll(CustomStack.getNamespacedIdsInRegistry().stream()
                                    .map(name -> name.toLowerCase(Locale.ROOT))
                                    .filter(name -> name.startsWith(partial))
                                    .limit(50)
                                    .collect(Collectors.toList()));
                        }
                    }
                }
                return completions;
            }

            if (args.length == 4) {
                String partial = args[3].toLowerCase(Locale.ROOT);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(partial))
                        completions.add(p.getName());
                }
                return completions;
            }
        }

        if (args[0].equalsIgnoreCase("give-block")) {
            return tabCompleteGiveBlock(args);
        }

        if (args[0].equalsIgnoreCase("blocks")) {
            return tabCompleteBlocks(args);
        }

        if (args[0].equalsIgnoreCase("backup")) {
            if (args.length == 2) {
                String partial = args[1].toLowerCase(Locale.ROOT);
                for (String value : List.of("export", "import")) addIfStartsWith(completions, value, partial);
            }
            return completions;
        }

        if (args[0].equalsIgnoreCase("migrate")) {
            if (args.length == 2) {
                String partial = args[1].toUpperCase(Locale.ROOT);
                if ("DRY-RUN".startsWith(args[1].toUpperCase(Locale.ROOT))) completions.add("dry-run");
                for (StorageSettings.StorageType type : StorageSettings.StorageType.values()) {
                    if (type.name().startsWith(partial)) completions.add(type.name());
                }
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("dry-run")) {
                String partial = args[2].toUpperCase(Locale.ROOT);
                for (StorageSettings.StorageType type : StorageSettings.StorageType.values()) {
                    if (type.name().startsWith(partial)) completions.add(type.name());
                }
            }
            return completions;
        }

        if (args[0].equalsIgnoreCase("repair")) {
            String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
            if (args.length == 2) {
                for (String value : List.of("scan", "export")) addIfStartsWith(completions, value, partial);
            } else if (args.length == 3) {
                for (String value : List.of("local", "sql", "current")) addIfStartsWith(completions, value, partial);
            }
            return completions;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("open") || args[0].equalsIgnoreCase("clear"))) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(partial))
                    completions.add(p.getName());
            }
            return completions;
        }

        return Collections.emptyList();
    }

    private List<String> tabCompleteGiveBlock(String[] args) {
        String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();

        if (args.length == 2) {
            addOnlinePlayers(suggestions, partial);
            addIfStartsWith(suggestions, "1", partial);
            addIfStartsWith(suggestions, "16", partial);
            addIfStartsWith(suggestions, "64", partial);
            addIfStartsWith(suggestions, "-s", partial);
            return suggestions;
        }

        if (args.length == 3) {
            addIfStartsWith(suggestions, "1", partial);
            addIfStartsWith(suggestions, "16", partial);
            addIfStartsWith(suggestions, "64", partial);
            if (!containsIgnoreCase(args, "-s")) addIfStartsWith(suggestions, "-s", partial);
            return suggestions;
        }

        if (args.length == 4 && !containsIgnoreCase(args, "-s")) {
            addIfStartsWith(suggestions, "-s", partial);
        }

        return suggestions;
    }

    private void addOnlinePlayers(List<String> suggestions, String partial) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            addIfStartsWith(suggestions, player.getName(), partial);
        }
    }

    private void addIfStartsWith(List<String> suggestions, String value, String partial) {
        if (value.toLowerCase(Locale.ROOT).startsWith(partial)) suggestions.add(value);
    }

    private boolean containsIgnoreCase(String[] args, String value) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    private GiveBlockArgs parseGiveBlockArgs(CommandSender sender, String[] args) {
        Player receiver = null;
        int amount = 1;
        boolean silent = false;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equalsIgnoreCase("-s")) {
                silent = true;
                continue;
            }

            try {
                amount = Integer.parseInt(arg);
                continue;
            } catch (NumberFormatException ignored) {
            }

            receiver = Bukkit.getPlayerExact(arg);
            if (receiver == null) {
                sender.sendMessage(VContainer.formatMessage(plugin.getMessageConfig().getString(
                        "admin-command.player-not-found",
                        "{prefix} The specific player not found! &8(&7{player}&8)"
                ).replace("{player}", arg)));
                return null;
            }
        }

        if (receiver == null && sender instanceof Player player) {
            receiver = player;
        }

        if (receiver == null) {
            sender.sendMessage(VContainer.formatMessage(plugin.getMessageConfig().getString("admin-command.need-a-player", "{prefix} Please enter a player!")));
            return null;
        }

        if (amount <= 0) {
            sender.sendMessage(VContainer.formatMessage(plugin.getMessageConfig().getString("admin-command.invalid-amount", "{prefix} Invalid amount: {amount}").replace("{amount}", String.valueOf(amount))));
            return null;
        }

        return new GiveBlockArgs(receiver, amount, silent);
    }

    private record GiveBlockArgs(Player receiver, int amount, boolean silent) {
    }

    private OfflinePlayer offlinePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayer(name);
    }

    private String playerName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private boolean require(CommandSender sender, String permission) {
        if (PermissionUtils.has(sender, permission)) return true;
        sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("admin-command.no-permission", "{prefix} You don't have any permission!")));
        return false;
    }

    private void reply(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(VContainer.formatMessage(message)));
    }

    private boolean handleBackup(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(VContainer.formatMessage("{prefix} Usage: &f/vcontainer backup <export|import> [name/file]"));
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("export")) {
            String name = args.length >= 3 ? args[2] : "";
            sender.sendMessage(VContainer.formatMessage("{prefix} Backup export started."));
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    manager.flushSync();
                    storageBlockManager.flushSync();
                    File file = AdminDataService.exportBackup(plugin, name);
                    AuditLogger.log("backup-export", sender, file.getName(), "path=" + file.getAbsolutePath());
                    reply(sender, "{prefix} Backup exported: &f" + file.getName());
                } catch (Exception e) {
                    reply(sender, "{prefix} Backup export failed: &c" + e.getMessage());
                }
            });
            return true;
        }

        if (action.equals("import")) {
            if (args.length < 3) {
                sender.sendMessage(VContainer.formatMessage("{prefix} Usage: &f/vcontainer backup import <file.zip>"));
                return true;
            }
            File file = new File(args[2]);
            if (!file.isAbsolute()) file = new File(new File(plugin.getDataFolder(), "backups"), args[2]);
            File importFile = file;
            Runnable importAction = () -> {
                sender.sendMessage(VContainer.formatMessage("{prefix} Backup import started. The plugin will lock until restart after a successful import."));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        File preImport = AdminDataService.exportBackup(plugin, "pre-import");
                        manager.setPersistenceSuspended(true);
                        storageBlockManager.setPersistenceSuspended(true);
                        AdminDataService.importBackup(plugin, importFile);
                        plugin.enterRestartRequiredMode("Backup import completed from " + importFile.getName() + ". Pre-import backup: " + preImport.getName());
                        AuditLogger.log("backup-import", sender, importFile.getName(), "path=" + importFile.getAbsolutePath() + " preImport=" + preImport.getName());
                        reply(sender, "{prefix} Backup imported. Restart the server before using VContainer again. Pre-import backup: &f" + preImport.getName());
                    } catch (Exception e) {
                        plugin.enterRestartRequiredMode("Backup import failed after import attempt from " + importFile.getName() + ". Check files before enabling VContainer again.");
                        reply(sender, "{prefix} Backup import failed after filesystem work: &c" + e.getMessage());
                        reply(sender, "{prefix} VContainer has been locked until restart to prevent cache overwrite.");
                    }
                });
            };
            if (sender instanceof Player player) {
                ConfirmGUI.open(player, "&0Confirm backup import", importAction);
            } else {
                importAction.run();
            }
            return true;
        }

        sender.sendMessage(VContainer.formatMessage("{prefix} Usage: &f/vcontainer backup <export|import> [name/file]"));
        return true;
    }

    private boolean handleMigrate(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(VContainer.formatMessage("{prefix} Usage: &f/vcontainer migrate [dry-run] <LOCAL|MYSQL|MARIADB|H2>"));
            return true;
        }

        boolean dryRun = args[1].equalsIgnoreCase("dry-run");
        int typeArg = dryRun ? 2 : 1;
        if (args.length <= typeArg) {
            sender.sendMessage(VContainer.formatMessage("{prefix} Usage: &f/vcontainer migrate [dry-run] <LOCAL|MYSQL|MARIADB|H2>"));
            return true;
        }

        StorageSettings.StorageType targetType;
        try {
            targetType = StorageSettings.StorageType.valueOf(args[typeArg].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(VContainer.formatMessage("{prefix} Unknown storage type: &f" + args[typeArg]));
            return true;
        }

        if (dryRun) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    AdminDataService.MigrationDryRun result = AdminDataService.dryRunMigration(plugin, manager, storageBlockManager, targetType);
                    reply(sender, "{prefix} Migration dry-run: &f" + result.message());
                    reply(sender, "{prefix} Source containers: &f" + result.playerContainers() + " &7storage blocks: &f" + result.storageBlocks());
                    reply(sender, "{prefix} Target existing player rows: &f" + result.existingPlayerRows() + " &7block rows: &f" + result.existingStorageBlockRows());
                    reply(sender, "{prefix} Target prefix: &f" + result.prefix());
                } catch (Exception e) {
                    reply(sender, "{prefix} Migration dry-run failed: &c" + e.getMessage());
                }
            });
            return true;
        }

        Runnable migrateAction = () -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File backup = AdminDataService.exportBackup(plugin, "pre-migration-" + targetType.name().toLowerCase(Locale.ROOT));
                AdminDataService.migrate(plugin, manager, storageBlockManager, targetType);
                AuditLogger.log("storage-migrate", sender, targetType.name(), "backup=" + backup.getName());
                reply(sender, "{prefix} Migration copied data to &f" + targetType.name() + "&7. Pre-migration backup: &f" + backup.getName());
                reply(sender, "{prefix} Update &fdatabase.yml&7 storage.Type and restart to use the new backend.");
            } catch (Exception e) {
                reply(sender, "{prefix} Migration failed: &c" + e.getMessage());
            }
        });

        if (sender instanceof Player player) {
            ConfirmGUI.open(player, "&0Confirm storage migration", migrateAction);
        } else {
            migrateAction.run();
        }
        return true;
    }

    private boolean handleRepair(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "scan";
        boolean export = action.equals("export");
        if (!action.equals("scan") && !action.equals("export")) {
            sender.sendMessage(VContainer.formatMessage("{prefix} Usage: &f/vcontainer repair <scan|export> [current|local|sql]"));
            return true;
        }

        String mode = args.length >= 3 ? args[2] : "current";
        sender.sendMessage(VContainer.formatMessage("{prefix} Repair " + action + " started."));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                AdminDataService.RepairReport report = AdminDataService.repair(plugin, mode, export);
                reply(sender, "{prefix} Repair checked &f" + report.checked() + " &7entries, bad: &f" + report.bad());
                reply(sender, "{prefix} " + report.message());
            } catch (Exception e) {
                reply(sender, "{prefix} Repair failed: &c" + e.getMessage());
            }
        });
        return true;
    }

    private boolean handleBlocks(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendBlocksUsage(sender);
            return true;
        }

        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                int page = parsePage(args, 2);
                List<StorageBlockManager.StorageBlock> blocks = new ArrayList<>(storageBlockManager.getStorageBlocks());
                blocks.sort(Comparator.comparing(StorageBlockManager.StorageBlock::key));
                int pageSize = 8;
                int pages = Math.max(1, (int) Math.ceil(blocks.size() / (double) pageSize));
                page = Math.max(1, Math.min(page, pages));
                sender.sendMessage(VContainer.formatMessage("{prefix} Storage blocks &8(&f" + page + "&7/&f" + pages + "&8)"));
                int from = (page - 1) * pageSize;
                int to = Math.min(blocks.size(), from + pageSize);
                for (int i = from; i < to; i++) {
                    StorageBlockManager.StorageBlock block = blocks.get(i);
                    sender.sendMessage(VContainer.formatMessage("&8- &f" + block.key() + " &7" + block.type().name()
                            + (block.ownerName() == null ? "" : " &8owner=&f" + block.ownerName())));
                }
                return true;
            }
            case "info" -> {
                StorageBlockManager.StorageBlock block = blockByArg(sender, args, 2);
                if (block == null) return true;
                sender.sendMessage(VContainer.formatMessage("{prefix} Key: &f" + block.key()));
                sender.sendMessage(VContainer.formatMessage("{prefix} Type: &f" + block.type()));
                sender.sendMessage(VContainer.formatMessage("{prefix} Owner: &f" + (block.ownerName() == null ? "-" : block.ownerName())));
                sender.sendMessage(VContainer.formatMessage("{prefix} Members: &f" + block.members().size()));
                return true;
            }
            case "tp" -> {
                if (!require(sender, "vcontainer.admin.blocks.tp")) return true;
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(VContainer.formatMessage(plugin.getMessageConfig().getString("command.only-players-can-use", "{prefix} Only players can use this command!")));
                    return true;
                }
                StorageBlockManager.StorageBlock block = blockByArg(sender, args, 2);
                if (block == null) return true;
                Location location = storageBlockManager.locationOf(block.key());
                if (location == null || location.getWorld() == null) {
                    sender.sendMessage(VContainer.formatMessage("{prefix} Storage block location is not loaded."));
                    return true;
                }
                player.teleport(location.clone().add(0.5, 1.0, 0.5));
                sender.sendMessage(VContainer.formatMessage("{prefix} Teleported to storage block."));
                return true;
            }
            case "remove" -> {
                if (!require(sender, "vcontainer.admin.blocks.remove")) return true;
                StorageBlockManager.StorageBlock block = blockByArg(sender, args, 2);
                if (block == null) return true;
                boolean keepBlock = containsIgnoreCase(args, "--keep-block");
                Runnable removeAction = () -> {
                    if (storageBlockManager.removeByKey(block.key(), keepBlock)) {
                        AuditLogger.log("storage-block-remove", sender, block.key(), "type=" + block.type() + " keepBlock=" + keepBlock);
                        sender.sendMessage(VContainer.formatMessage("{prefix} Storage block removed."));
                    } else {
                        sender.sendMessage(VContainer.formatMessage("{prefix} Could not remove storage block."));
                    }
                };
                if (sender instanceof Player player) {
                    ConfirmGUI.open(player, "&0Confirm block removal", removeAction);
                } else {
                    removeAction.run();
                }
                return true;
            }
            case "owner" -> {
                if (!require(sender, "vcontainer.admin.blocks.owner")) return true;
                if (args.length < 4) {
                    sender.sendMessage(VContainer.formatMessage("{prefix} Usage: &f/vcontainer blocks owner <key> <player>"));
                    return true;
                }
                StorageBlockManager.StorageBlock block = blockByArg(sender, args, 2);
                if (block == null) return true;
                Player target = Bukkit.getPlayerExact(args[3]);
                if (target == null) {
                    sender.sendMessage(VContainer.formatMessage(plugin.getMessageConfig().getString("admin-command.player-not-found", "{prefix} The specific player not found! &8(&7{player}&8)").replace("{player}", args[3])));
                    return true;
                }
                if (storageBlockManager.setOwner(block.key(), target.getUniqueId(), target.getName())) {
                    AuditLogger.log("storage-block-owner", sender, block.key(), "owner=" + target.getName() + " uuid=" + target.getUniqueId());
                    sender.sendMessage(VContainer.formatMessage("{prefix} Storage block owner updated."));
                } else {
                    sender.sendMessage(VContainer.formatMessage("{prefix} Only personal storage blocks have owners."));
                }
                return true;
            }
            case "members" -> {
                if (!require(sender, "vcontainer.admin.blocks.members")) return true;
                return handleBlockMembers(sender, args);
            }
            default -> {
                sendBlocksUsage(sender);
                return true;
            }
        }
    }

    private boolean handleBlockMembers(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(VContainer.formatMessage("{prefix} Usage: &f/vcontainer blocks members <key> <list|add|remove> [player]"));
            return true;
        }
        StorageBlockManager.StorageBlock block = blockByArg(sender, args, 2);
        if (block == null) return true;
        String action = args[3].toLowerCase(Locale.ROOT);
        if (action.equals("list")) {
            sender.sendMessage(VContainer.formatMessage("{prefix} Members: &f" + block.members().stream().map(UUID::toString).collect(Collectors.joining(", "))));
            return true;
        }
        if (args.length < 5) {
            sender.sendMessage(VContainer.formatMessage("{prefix} Missing player."));
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[4]);
        if (target == null) {
            sender.sendMessage(VContainer.formatMessage(plugin.getMessageConfig().getString("admin-command.player-not-found", "{prefix} The specific player not found! &8(&7{player}&8)").replace("{player}", args[4])));
            return true;
        }
        boolean member = action.equals("add");
        if (!member && !action.equals("remove")) {
            sender.sendMessage(VContainer.formatMessage("{prefix} Unknown members action."));
            return true;
        }
        if (storageBlockManager.setMember(block.key(), target.getUniqueId(), member)) {
            AuditLogger.log(member ? "storage-block-member-add" : "storage-block-member-remove", sender, block.key(), "member=" + target.getName() + " uuid=" + target.getUniqueId());
            sender.sendMessage(VContainer.formatMessage("{prefix} Storage block members updated."));
        } else {
            sender.sendMessage(VContainer.formatMessage("{prefix} Could not update members."));
        }
        return true;
    }

    private StorageBlockManager.StorageBlock blockByArg(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            sender.sendMessage(VContainer.formatMessage("{prefix} Missing storage block key."));
            return null;
        }
        StorageBlockManager.StorageBlock block = storageBlockManager.get(args[index]);
        if (block == null) {
            sender.sendMessage(VContainer.formatMessage("{prefix} Storage block not found."));
        }
        return block;
    }

    private int parsePage(String[] args, int index) {
        if (args.length <= index) return 1;
        try {
            return Integer.parseInt(args[index]);
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private void sendBlocksUsage(CommandSender sender) {
        sender.sendMessage(VContainer.formatMessage("{prefix} &f/vcontainer blocks list [page]"));
        sender.sendMessage(VContainer.formatMessage("{prefix} &f/vcontainer blocks info <key>"));
        sender.sendMessage(VContainer.formatMessage("{prefix} &f/vcontainer blocks tp <key>"));
        sender.sendMessage(VContainer.formatMessage("{prefix} &f/vcontainer blocks remove <key> [--keep-block]"));
        sender.sendMessage(VContainer.formatMessage("{prefix} &f/vcontainer blocks owner <key> <player>"));
        sender.sendMessage(VContainer.formatMessage("{prefix} &f/vcontainer blocks members <key> <list|add|remove> [player]"));
    }

    private List<String> tabCompleteBlocks(String[] args) {
        String partial = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        if (args.length == 2) {
            for (String value : List.of("list", "info", "tp", "remove", "owner", "members")) addIfStartsWith(suggestions, value, partial);
            return suggestions;
        }
        if (args.length == 3 && !args[1].equalsIgnoreCase("list")) {
            for (StorageBlockManager.StorageBlock block : storageBlockManager.getStorageBlocks()) addIfStartsWith(suggestions, block.key(), partial);
            return suggestions;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("members")) {
            for (String value : List.of("list", "add", "remove")) addIfStartsWith(suggestions, value, partial);
            return suggestions;
        }
        if ((args.length == 4 && args[1].equalsIgnoreCase("owner")) || (args.length == 5 && args[1].equalsIgnoreCase("members"))) {
            addOnlinePlayers(suggestions, partial);
        }
        return suggestions;
    }
}
