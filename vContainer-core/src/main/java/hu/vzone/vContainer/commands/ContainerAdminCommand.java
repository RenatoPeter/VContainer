package hu.vzone.vContainer.commands;

import hu.vzone.vContainer.VContainer;
import hu.vzone.vContainer.gui.ContainerGUI;
import hu.vzone.vContainer.managers.ContainerManager;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.core.items.MythicItem;
import io.th0rgal.oraxen.api.OraxenItems;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

public class ContainerAdminCommand implements CommandExecutor, TabCompleter {

    private final VContainer plugin;
    private final ContainerManager manager;
    private final boolean hasOraxen;
    private final boolean hasMythicMobs;

    public ContainerAdminCommand(VContainer plugin, ContainerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.hasOraxen = Bukkit.getPluginManager().isPluginEnabled("Oraxen");
        this.hasMythicMobs = Bukkit.getPluginManager().isPluginEnabled("MythicMobs");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vcontainer.admin")) {
            sender.sendMessage("§cNincs jogod ehhez a parancshoz.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§7Használat: §e/vcontainer <open|clear|reload|give>");
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);

        switch (action) {
            case "reload":
                plugin.reloadConfig();
                sender.sendMessage("§aA VContainer konfiguráció újratöltve!");
                return true;

            case "open":
            case "clear":
                if (args.length < 2) {
                    sender.sendMessage("§cHasználat: /vcontainer " + action + " <player>");
                    return true;
                }

                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("§cA játékos nem található vagy offline.");
                    return true;
                }

                if (action.equals("open")) {
                    ContainerGUI.openContainer(target, manager, 1);
                    sender.sendMessage("§aMegnyitva §f" + target.getName() + " §acontainer-e.");
                } else {
                    manager.clearContainer(target);
                    sender.sendMessage("§eTörölve §f" + target.getName() + " §econtainer-e.");
                }
                return true;

            case "give":
                if (args.length < 3) {
                    sender.sendMessage("§cHasználat: /vcontainer give <minecraft|oraxen|mythicmobs> <item> [player] [amount]");
                    return true;
                }

                String source = args[1].toLowerCase(Locale.ROOT);
                String itemName = args[2];
                Player receiver = null;
                int amount = 1;

                if (args.length >= 4)
                    receiver = Bukkit.getPlayerExact(args[3]);
                if (args.length >= 5) {
                    try {
                        amount = Integer.parseInt(args[4]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cÉrvénytelen mennyiség: " + args[4]);
                        return true;
                    }
                }

                if (receiver == null && sender instanceof Player)
                    receiver = (Player) sender;

                if (receiver == null) {
                    sender.sendMessage("§cKérlek adj meg egy játékost!");
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
                            item = OraxenItems.getItemById(itemName).build();
                            if (item != null)
                                item.setAmount(amount);
                        }
                    }

                    case "mythicmobs" -> {
                        if (hasMythicMobs) {
                            MythicItem mythicItem = MythicBukkit.inst().getItemManager().getItem(itemName).orElse(null);
                            if (mythicItem != null) {
                                // 5.9.x API: generateItemStack() már közvetlenül Bukkit ItemStack-et ad
                                item = (ItemStack) mythicItem.generateItemStack(amount);
                            }
                        }
                    }

                    default -> {
                        sender.sendMessage("§cIsmeretlen forrás: " + source);
                        return true;
                    }
                }

                if (item == null) {
                    sender.sendMessage("§cNem található item: " + itemName);
                    return true;
                }

                manager.addItemToContainer(receiver, item);
                sender.sendMessage("§aHozzáadva §f" + amount + "x " + itemName + " §aa §f" + receiver.getName() + " §acontaineréhez!");
                return true;

            default:
                sender.sendMessage("§cIsmeretlen művelet: " + action);
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("vcontainer.admin")) return Collections.emptyList();
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            for (String s : List.of("open", "clear", "reload", "give")) {
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
                        if (hasMythicMobs) {
                            completions.addAll(MythicBukkit.inst().getItemManager().getItems().stream()
                                    .map(MythicItem::getInternalName)
                                    .map(name -> name.toLowerCase(Locale.ROOT))
                                    .filter(name -> name.startsWith(partial))
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
}
