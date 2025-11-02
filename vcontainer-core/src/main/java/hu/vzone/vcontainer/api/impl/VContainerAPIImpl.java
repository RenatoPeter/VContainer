package hu.vzone.vcontainer.api.impl;

import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.api.VContainerAPI;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class VContainerAPIImpl implements VContainerAPI {
    private final ContainerManager manager;

    public VContainerAPIImpl(ContainerManager manager) {
        this.manager = manager;
    }

    @Override
    public void addItem(Player player, ItemStack item) {
        manager.addItemToContainer(player, item);
    }

    @Override
    public void removeItem(Player player, ItemStack item) {
        manager.removeItemFromContainer(player, item);
    }

    @Override
    public List<ItemStack> getItems(Player player) {
        return manager.getAllItemFromContainer(player);
    }

    @Override
    public void clear(Player player) {
        manager.clearContainer(player);
    }
}
