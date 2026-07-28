package hu.vzone.vcontainer.listeners;

import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.managers.StorageBlockManager;
import net.vortexdevelopment.vortexminions.api.chest.MinionChest;
import net.vortexdevelopment.vortexminions.api.event.MinionBlockMineEvent;
import net.vortexdevelopment.vortexminions.api.event.MinionChestLinkEvent;
import net.vortexdevelopment.vortexminions.api.event.MinionItemTransferEvent;
import net.vortexdevelopment.vortexminions.api.minion.Minion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class VortexMinionsHookListener implements Listener {
    private final ContainerManager containerManager;
    private final StorageBlockManager storageBlockManager;

    public VortexMinionsHookListener(
            ContainerManager containerManager,
            StorageBlockManager storageBlockManager
    ) {
        this.containerManager = containerManager;
        this.storageBlockManager = storageBlockManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChestLink(MinionChestLinkEvent event) {
        Location clickedLocation = event.getLocation();
        if (clickedLocation == null) return;

        Block block = clickedLocation.getBlock();
        StorageBlockManager.StorageBlock storageBlock = storageBlockManager.get(block);
        if (storageBlock == null) {
            return;
        }

        event.setHandled(true);

        if (storageBlock.type() != StorageBlockManager.StorageType.PERSONAL) {
            event.setCancelled(true);
            return;
        }

        Minion minion = event.getMinion();
        if (minion == null || !canLink(minion, storageBlock)) {
            event.setCancelled(true);
            return;
        }

        event.setChest(new VContainerMinionChest(minion, block.getLocation(), storageBlock.ownerId()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockMine(MinionBlockMineEvent event) {
        Block block = event.getBlock();
        if (block == null || !storageBlockManager.isStorageBlock(block)) {
            return;
        }
        event.setPreventBlockBreak(true);
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemTransfer(MinionItemTransferEvent event) {
        if (event.getTransferType() != MinionItemTransferEvent.TransferType.STORAGE) {
            return;
        }

        Minion minion = event.getMinion();
        if (minion == null) return;

        Optional<MinionChest> connectedChest = minion.getConnectedChest();
        if (connectedChest.isEmpty() || !(connectedChest.get() instanceof VContainerMinionChest customChest)) {
            return;
        }

        List<ItemStack> items = event.getItems();
        if (items == null || items.isEmpty()) {
            event.setHandled(true);
            event.setItems(new ArrayList<>());
            event.setDropOverflow(false);
            return;
        }

        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack item : items) {
            ItemStack leftover = customChest.deposit(item);
            if (leftover != null && !leftover.getType().isAir() && leftover.getAmount() > 0) {
                leftovers.add(leftover);
            }
        }

        event.setItems(leftovers);
        event.setHandled(true);
        event.setDropOverflow(!leftovers.isEmpty());
    }

    private boolean canLink(Minion minion, StorageBlockManager.StorageBlock storageBlock) {
        UUID minionOwnerId = minion.getOwnerId();
        if (minionOwnerId == null) return false;
        if (storageBlock.ownerId().equals(minionOwnerId) || storageBlock.members().contains(minionOwnerId)) {
            return true;
        }

        Player onlineOwner = Bukkit.getPlayer(minionOwnerId);
        return onlineOwner != null && storageBlockManager.canManage(onlineOwner, storageBlock);
    }

    private final class VContainerMinionChest implements MinionChest {
        private final UUID uniqueId = UUID.randomUUID();
        private final Minion minion;
        private final Location location;
        private final UUID containerOwnerId;
        private boolean active = true;

        private VContainerMinionChest(Minion minion, Location location, UUID containerOwnerId) {
            this.minion = minion;
            this.location = location.clone();
            this.containerOwnerId = containerOwnerId;
        }

        @Override
        public UUID getUniqueId() {
            return uniqueId;
        }

        @Override
        public Minion getMinion() {
            return minion;
        }

        @Override
        public Location getLocation() {
            return location.clone();
        }

        @Override
        public UUID getOwner() {
            return minion.getOwnerId();
        }

        @Override
        public boolean isActive() {
            return active && isStorageStillValid();
        }

        @Override
        public void setActive(boolean active) {
            this.active = active;
        }

        public ItemStack deposit(ItemStack item) {
            if (!isActive() || item == null || item.getType().isAir() || item.getAmount() <= 0) {
                return item;
            }

            ItemStack moving = item.clone();
            int added = containerManager.addItemToContainer(containerOwnerId, moving);
            if (added <= 0) {
                return moving;
            }

            if (added >= moving.getAmount()) {
                return null;
            }

            moving.setAmount(moving.getAmount() - added);
            return moving;
        }

        private boolean isStorageStillValid() {
            StorageBlockManager.StorageBlock storageBlock = storageBlockManager.get(location.getBlock());
            return storageBlock != null
                    && storageBlock.type() == StorageBlockManager.StorageType.PERSONAL
                    && storageBlock.ownerId().equals(containerOwnerId);
        }
    }
}
