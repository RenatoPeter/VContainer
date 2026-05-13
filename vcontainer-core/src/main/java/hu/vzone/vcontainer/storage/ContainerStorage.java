package hu.vzone.vcontainer.storage;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ContainerStorage extends AutoCloseable {
    Map<UUID, List<ItemStack>> loadAll();

    boolean save(UUID ownerId, List<ItemStack> items);

    @Override
    void close();
}
