package net.brcdev.myeconomy;

import net.brcdev.myeconomy.command.MyEconomyCommand;
import net.brcdev.myeconomy.hook.ShopGUIPlusHook;
import net.brcdev.myeconomy.provider.MyEconomyProvider;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class MyEconomyPlugin extends JavaPlugin {

  private MyEconomyProvider myEconomyProvider;
  private ShopGUIPlusHook shopGUIPlusHook;

  @Override
  public void onEnable() {
    this.myEconomyProvider = new MyEconomyProvider();
    getCommand("myeconomy").setExecutor(new MyEconomyCommand(this));

    hookIntoShopGUIPlus();
  }

  public MyEconomyProvider getMyEconomyProvider() {
    return myEconomyProvider;
  }

  private void hookIntoShopGUIPlus() {
    if (Bukkit.getPluginManager().getPlugin("ShopGUIPlus") != null) {
      this.shopGUIPlusHook = new ShopGUIPlusHook(this);
      Bukkit.getPluginManager().registerEvents(shopGUIPlusHook, this);

      this.getLogger().info("ShopGUI+ detected.");
    } else {
      this.getLogger().warning("ShopGUI+ not found.");
    }
  }
}
