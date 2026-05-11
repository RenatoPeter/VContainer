package hu.vzone.vcontainer.commands;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.gui.ContainerGUI;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.utils.PermissionUtils;
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
        if(!PermissionUtils.has(player, "vcontainer.use")){
            sender.sendMessage(plugin.formatMessage(plugin.getMessageConfig().getString("command.no-permission", "{prefix} You don't have any permission!")));
            return true;
        }
        ContainerGUI.openContainer(player, manager, 1);
        return true;
    }
}
