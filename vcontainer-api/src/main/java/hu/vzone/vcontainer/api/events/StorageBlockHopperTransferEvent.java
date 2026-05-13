package hu.vzone.vcontainer.api.events;

import org.bukkit.block.Block;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class StorageBlockHopperTransferEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID ownerId;
    private final Block hopper;
    private final ItemStack item;
    private final Direction direction;
    private boolean cancelled;

    public StorageBlockHopperTransferEvent(UUID ownerId, Block hopper, ItemStack item, Direction direction) {
        this.ownerId = ownerId;
        this.hopper = hopper;
        this.item = item.clone();
        this.direction = direction;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public Block getHopper() {
        return hopper;
    }

    public ItemStack getItem() {
        return item.clone();
    }

    public Direction getDirection() {
        return direction;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public enum Direction {
        INTO_CONTAINER,
        OUT_OF_CONTAINER
    }
}
