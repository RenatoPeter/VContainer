package net.brcdev.myitems.shopguiplus;

import net.brcdev.myitems.MyItemsPlugin;
import net.brcdev.myitems.shopguiplus.provider.MyItemsProvider;
import net.brcdev.shopgui.ShopGuiPlusApi;
import net.brcdev.shopgui.event.ShopGUIPlusPostEnableEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ShopGUIPlusHook implements Listener {

  private MyItemsPlugin myItemsPlugin;
  private MyItemsProvider myItemsProvider;

  public ShopGUIPlusHook(MyItemsPlugin myItemsPlugin) {
    this.myItemsPlugin = myItemsPlugin;
  }

  @EventHandler
  public void onShopGUIPlusPostEnable(ShopGUIPlusPostEnableEvent event) {
    this.myItemsProvider = new MyItemsProvider();
    ShopGuiPlusApi.registerItemProvider(myItemsProvider);
    myItemsPlugin.getLogger().info("Registered item provider in ShopGUI+!");
  }
}
