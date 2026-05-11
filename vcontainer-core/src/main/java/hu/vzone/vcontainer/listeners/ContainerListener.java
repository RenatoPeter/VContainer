package hu.vzone.vcontainer.listeners;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.gui.ContainerGUI;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.managers.StorageBlockManager;
import hu.vzone.vcontainer.managers.StorageBlockManager.StorageBlock;
import hu.vzone.vcontainer.managers.StorageBlockManager.StorageType;
import hu.vzone.vcontainer.utils.PermissionUtils;
import hu.vzone.vcontainer.utils.StorageBlockItem;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.SculkShrieker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

public class ContainerListener implements Listener {

    private final ContainerManager manager;
    private final StorageBlockManager storageBlockManager;

    public ContainerListener(ContainerManager manager, StorageBlockManager storageBlockManager) {
        this.manager = manager;
        this.storageBlockManager = storageBlockManager;
    }

    @EventHandler
    public void onStorageBlockInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        StorageBlock storageBlock = storageBlockManager.get(block);
        if (storageBlock == null) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!PermissionUtils.has(player, "vcontainer.block.use")) {
            send(player, "storage-block.no-use-permission", "{prefix} You don't have permission to use this storage block.");
            return;
        }

        if (storageBlock.type() == StorageType.PERSONAL) {
            if (!storageBlockManager.canAccess(player, storageBlock)) {
                send(player, "storage-block.no-access", "{prefix} You are not added to this storage block.");
                return;
            }
            ContainerGUI.openContainerForStorage(player, storageBlock.ownerId(), storageBlock.ownerName(), manager, storageBlockManager, storageBlock.key());
            return;
        }

        ContainerGUI.openContainer(player, manager, 1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onStorageBlockPlace(BlockPlaceEvent event) {
        if (!StorageBlockItem.isStorageBlockItem(VContainer.getInstance(), event.getItemInHand())) return;

        if (!storageBlockManager.addPersonal(event.getBlockPlaced(), event.getPlayer())) {
            event.setCancelled(true);
            return;
        }

        BlockData blockData = event.getBlockPlaced().getBlockData();
        if (blockData instanceof SculkShrieker shrieker) {
            shrieker.setCanSummon(false);
            event.getBlockPlaced().setBlockData(shrieker);
        }

        send(event.getPlayer(), "storage-block.placed", "{prefix} Personal storage block placed.");
    }

    @EventHandler
    public void onStorageBlockBreak(BlockBreakEvent event) {
        if (!storageBlockManager.isStorageBlock(event.getBlock())) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        if (storageBlock.type() == StorageType.PERSONAL) {
            send(player, "storage-block.personal-break-protected", "{prefix} Pick up this storage block from its menu.");
            return;
        }

        if (!player.isSneaking() || !PermissionUtils.has(player, "vcontainer.block.remove")) {
            send(player, "storage-block.protected", "{prefix} This storage block is protected.");
            return;
        }

        storageBlockManager.removeGlobal(event.getBlock());
        send(player, "storage-block.removed", "{prefix} Storage block removed.");
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(storageBlockManager::isStorageBlock);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(storageBlockManager::isStorageBlock);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.clearCacheFor(event.getPlayer().getUniqueId());
        ContainerGUI.clearSortPreference(event.getPlayer().getUniqueId());
    }

    private void send(Player player, String path, String fallback) {
        VContainer plugin = VContainer.getInstance();
        player.sendMessage(VContainer.formatMessage(plugin.getMessageConfig().getString(path, fallback)));
    }
}
