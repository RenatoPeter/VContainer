package hu.vzone.vContainer.commands;

import hu.vzone.vContainer.VContainer;
import hu.vzone.vContainer.gui.ContainerGUI;
import hu.vzone.vContainer.managers.ContainerManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ContainerAdminCommand implements CommandExecutor, TabCompleter {
    private final VContainer plugin;
    private final ContainerManager manager;

    public ContainerAdminCommand(VContainer plugin, ContainerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vcontainer.admin")) {
            sender.sendMessage("§cNincs jogod ehhez a parancshoz.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§7Használat: §e/vcontainer <open|clear|reload> [player]");
            return true;
        }

        String action = args[0].toLowerCase();

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
                } else if (action.equals("clear")) {
                    manager.clearContainer(target);
                    sender.sendMessage("§eTörölve §f" + target.getName() + " §econtainer-e.");
                }
                return true;

            default:
                sender.sendMessage("§cIsmeretlen művelet: " + action);
                sender.sendMessage("§7Használat: §e/vcontainer <open|clear|reload> [player]");
                return true;
        }
    }


    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("vcontainer.admin")) return Collections.emptyList();

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            List<String> options = List.of("open", "clear", "reload");
            for (String opt : options) {
                if (opt.startsWith(partial)) completions.add(opt);
            }
            return completions;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("open") || args[0].equalsIgnoreCase("clear"))) {
            String partial = args[1].toLowerCase();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) {
                    completions.add(p.getName());
                }
            }
            return completions;
        }

        return Collections.emptyList();
    }
}
