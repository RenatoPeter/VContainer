package net.brcdev.myitems.shopguiplus.provider;

import net.brcdev.shopgui.provider.item.ItemProvider;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

public class MyItemsProvider extends ItemProvider {

  public MyItemsProvider() {
    // You must pass the name of your item provider in the superclass constructor
    super("MyItems");
  }

  @Override
  public boolean isValidItem(ItemStack itemStack) {
    return getCustomId(itemStack) != null;
  }

  @Override
  public ItemStack loadItem(ConfigurationSection configurationSection) {
    String myItemsId = configurationSection.getString("myItems");

    if (myItemsId == null) return null;

    ItemStack itemStack = new ItemStack(Material.DIAMOND, 1);

    ItemMeta itemMeta = itemStack.getItemMeta();
    itemMeta.setLore(Arrays.asList("MyItems: " + myItemsId));
    itemStack.setItemMeta(itemMeta);

    return itemStack;
  }

  @Override
  public boolean compare(ItemStack itemStack, ItemStack itemStack1) {
    return Objects.equals(getCustomId(itemStack), getCustomId(itemStack1));
  }

  private String getCustomId(ItemStack itemStack) {
    if (!itemStack.hasItemMeta()) return null;

    ItemMeta itemMeta = itemStack.getItemMeta();

    if (!itemMeta.hasLore()) return null;

    // We're using the most basic way to identify items - by adding ID to their lores
    for (String line : itemMeta.getLore()) {
      if (line.startsWith("MyItems: ")) {
        return line.split("MyItems: ")[0];
      }
    }

    return null;
  }
}
