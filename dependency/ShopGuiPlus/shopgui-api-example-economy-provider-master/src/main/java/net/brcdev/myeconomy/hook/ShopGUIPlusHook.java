package net.brcdev.myeconomy.hook;

import net.brcdev.myeconomy.MyEconomyPlugin;
import net.brcdev.shopgui.ShopGuiPlusApi;
import net.brcdev.shopgui.event.ShopGUIPlusPostEnableEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ShopGUIPlusHook implements Listener {

  private MyEconomyPlugin myEconomyPlugin;

  public ShopGUIPlusHook(MyEconomyPlugin myEconomyPlugin) {
    this.myEconomyPlugin = myEconomyPlugin;
  }

  @EventHandler
  public void onShopGUIPlusPostEnable(ShopGUIPlusPostEnableEvent event) {
    ShopGuiPlusApi.registerEconomyProvider(myEconomyPlugin.getMyEconomyProvider());
    myEconomyPlugin.getLogger().info("Registered economy provider in ShopGUI+!");
  }
}
