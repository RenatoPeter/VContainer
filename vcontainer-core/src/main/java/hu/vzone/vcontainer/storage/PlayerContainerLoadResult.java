package hu.vzone.vcontainer.storage;

import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;

/** Result of one owner-scoped container load. An empty successful list is a valid new container. */
public record PlayerContainerLoadResult(boolean successful, List<ItemStack> items, String error) {
    public static PlayerContainerLoadResult success(List<ItemStack> items) {
        return new PlayerContainerLoadResult(true, items == null ? Collections.emptyList() : items, "");
    }

    public static PlayerContainerLoadResult failure(String error) {
        return new PlayerContainerLoadResult(false, Collections.emptyList(), error == null ? "Unknown storage load failure" : error);
    }
}
