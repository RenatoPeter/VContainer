package net.brcdev.myeconomy.command;

import net.brcdev.myeconomy.MyEconomyPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MyEconomyCommand implements CommandExecutor {

  private MyEconomyPlugin plugin;

  public MyEconomyCommand(MyEconomyPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player)) {
      sender.sendMessage("This command can be used only in game.");
      return true;
    }

    Player player = (Player) sender;
    sender.sendMessage("Your balance is $" + plugin.getMyEconomyProvider().getBalance(player) + ".");
    return true;
  }
}
