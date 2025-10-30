package hu.vzone.vcontainer.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public interface VContainerAPI {
    void addItem(Player player, ItemStack item);
    void removeItem(Player player, ItemStack item);
    List<ItemStack> getItems(Player player);
    void clear(Player player);
}
