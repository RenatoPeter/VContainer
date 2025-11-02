package hu.vzone.vcontainer.utils;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class ContainerHolder implements InventoryHolder {

    private final String ownerName; // a container tulajdonosának neve

    public ContainerHolder(String ownerName) {
        this.ownerName = ownerName;
    }

    public String getOwner() {
        return ownerName;
    }

    @Override
    public Inventory getInventory() {
        return null; // nem kell tényleges inventoryt visszaadnunk
    }
}
