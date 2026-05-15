package net.brcdev.myitems;

import net.brcdev.myitems.shopguiplus.ShopGUIPlusHook;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class MyItemsPlugin extends JavaPlugin {

  private ShopGUIPlusHook shopGUIPlusHook;

  @Override
  public void onEnable() {
    hookIntoShopGUIPlus();
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
