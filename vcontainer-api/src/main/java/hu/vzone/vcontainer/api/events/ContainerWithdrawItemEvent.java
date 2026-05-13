package hu.vzone.vcontainer.api.events;

import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class ContainerWithdrawItemEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID ownerId;
    private final ItemStack item;
    private final int requestedAmount;
    private boolean cancelled;

    public ContainerWithdrawItemEvent(UUID ownerId, ItemStack item, int requestedAmount) {
        this.ownerId = ownerId;
        this.item = item.clone();
        this.requestedAmount = requestedAmount;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public ItemStack getItem() {
        return item.clone();
    }

    public int getRequestedAmount() {
        return requestedAmount;
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
}
