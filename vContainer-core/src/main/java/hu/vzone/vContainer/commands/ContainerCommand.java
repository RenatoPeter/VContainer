package hu.vzone.vContainer.commands;

import hu.vzone.vContainer.gui.ContainerGUI;
import hu.vzone.vContainer.managers.ContainerManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ContainerCommand implements CommandExecutor {
    private final ContainerManager manager;


    public ContainerCommand(ContainerManager manager) { this.manager = manager; }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command");
            return true;
        }
        Player player = (Player) sender;
        ContainerGUI.openContainer(player, manager, 1);
        return true;
    }
}
