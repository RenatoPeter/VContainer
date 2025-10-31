package hu.vzone.vContainer.commands;

import hu.vzone.vContainer.VContainer;
import hu.vzone.vContainer.gui.ContainerGUI;
import hu.vzone.vContainer.managers.ContainerManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ContainerCommand implements CommandExecutor {
    private final ContainerManager manager;
    private final VContainer plugin;


    public ContainerCommand(VContainer plugin, ContainerManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("command.only-players-can-use", "{prefix} Only players can use this command!")));
            return true;
        }
        if(!player.hasPermission("vcontainer.use")){
            sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("command.no-permission", "{prefix} You don't have any permission!")));
            return true;
        }
        ContainerGUI.openContainer(player, manager, 1);
        return true;
    }
}
