package hu.vzone.vContainer.utils;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class ContainerHolder implements InventoryHolder {

    private final String playerName;

    public ContainerHolder(String playerName) {
        this.playerName = playerName;
    }

    public String getPlayerName() {
        return playerName;
    }

    @Override
    public Inventory getInventory() {
        return null;
    }
}
