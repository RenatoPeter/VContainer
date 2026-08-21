package hu.vzone.vcontainer.storage;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

public interface ContainerStorage extends AutoCloseable {
    /** Loads one owner only. A failed load must never be represented as a valid empty container. */
    PlayerContainerLoadResult load(UUID ownerId);

    boolean save(UUID ownerId, List<ItemStack> items);

    /** Saves one changed-cache snapshot. SQL implementations should keep the complete call atomic. */
    default boolean saveAll(java.util.Map<UUID, List<ItemStack>> containers) {
        for (java.util.Map.Entry<UUID, List<ItemStack>> entry : containers.entrySet()) {
            if (!save(entry.getKey(), entry.getValue())) return false;
        }
        return true;
    }

    @Override
    void close();
}
