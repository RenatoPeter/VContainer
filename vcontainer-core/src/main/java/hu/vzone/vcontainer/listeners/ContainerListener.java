package hu.vzone.vcontainer.listeners;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.gui.ContainerGUI;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.managers.StorageBlockManager;
import hu.vzone.vcontainer.managers.StorageBlockManager.StorageBlock;
import hu.vzone.vcontainer.managers.StorageBlockManager.StorageType;
import hu.vzone.vcontainer.utils.PermissionUtils;
import hu.vzone.vcontainer.utils.SkinProvider;
import hu.vzone.vcontainer.utils.StorageBlockItem;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.SculkShrieker;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

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

        Player player = event.getPlayer();
        if (VContainer.getInstance().isRestartRequired()) {
            event.setCancelled(true);
            send(player, "command.restart-required", "{prefix} VContainer is waiting for a server restart.");
            return;
        }
        if (storageBlock.type() == StorageType.PERSONAL && player.isSneaking()) {
            if (!PermissionUtils.has(player, "vcontainer.block.use")) {
                event.setCancelled(true);
                send(player, "storage-block.no-use-permission", "{prefix} You don't have permission to use this storage block.");
                return;
            }
            if (!storageBlockManager.canAccess(player, storageBlock)) {
                event.setCancelled(true);
                send(player, "storage-block.no-access", "{prefix} You are not added to this storage block.");
                return;
            }
            if (tryAttachHopper(event, block, player)) {
                event.setCancelled(true);
            }
            return;
        }

        event.setCancelled(true);

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
        if (VContainer.getInstance().isRestartRequired()) {
            if (StorageBlockItem.isStorageBlockItem(VContainer.getInstance(), event.getItemInHand())) {
                event.setCancelled(true);
                send(event.getPlayer(), "command.restart-required", "{prefix} VContainer is waiting for a server restart.");
            }
            return;
        }
        if (event.getBlockPlaced().getType() == Material.HOPPER) {
            refreshNearbyHopperLinks(event.getBlockPlaced());
        }

        if (!StorageBlockItem.isStorageBlockItem(VContainer.getInstance(), event.getItemInHand())) return;

        if (!storageBlockManager.canPlacePersonal(event.getBlockPlaced(), event.getPlayer())) {
            event.setCancelled(true);
            int limit = storageBlockManager.personalChunkLimit();
            send(event.getPlayer(), "storage-block.chunk-limit", "{prefix} You can only place {limit} personal storage blocks in this chunk."
                    .replace("{limit}", String.valueOf(limit)));
            return;
        }

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
        StorageBlock storageBlock = storageBlockManager.get(event.getBlock());
        if (storageBlock == null) return;

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
        SkinProvider.clear(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        SkinProvider.cache(event.getPlayer());
        VContainer plugin = VContainer.getInstance();
        if (plugin == null) return;
        manager.loadPlayer(event.getPlayer());
        if (!plugin.getConfig().getBoolean("update-checker.enabled", true)) return;
        if (!event.getPlayer().isOp()) return;
        if (!plugin.getUpdateChecker().wasChecked() || !plugin.getUpdateChecker().isUpdateAvailable()) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> event.getPlayer().sendMessage(VContainer.formatMessage(
                event.getPlayer(),
                "{prefix} New version available: &f" + plugin.getUpdateChecker().getLatestVersion()
                        + " &7(Current: &f" + plugin.getDescription().getVersion() + "&7)"
        )), 40L);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        storageBlockManager.handleChunkLoad(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        storageBlockManager.handleChunkUnload(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    private void send(Player player, String path, String fallback) {
        VContainer plugin = VContainer.getInstance();
        player.sendMessage(VContainer.formatMessage(player, plugin.getMessageConfig().getString(path, fallback)));
    }

    private boolean tryAttachHopper(PlayerInteractEvent event, Block storageBlock, Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() != Material.HOPPER) return false;

        BlockFace face = event.getBlockFace();
        Block target = storageBlock.getRelative(face);
        if (!target.getType().isAir()) return false;

        BlockFace hopperFacing = face.getOppositeFace();
        BlockData data = Bukkit.createBlockData(Material.HOPPER);
        if (data instanceof Directional directional) {
            if (!directional.getFaces().contains(hopperFacing)) return false;
            directional.setFacing(hopperFacing);
            data = directional;
        }

        BlockState replacedState = target.getState();
        target.setType(Material.HOPPER, false);
        target.setBlockData(data, false);

        BlockPlaceEvent placeEvent = new BlockPlaceEvent(target, replacedState, storageBlock, item.clone(), player, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(placeEvent);
        if (placeEvent.isCancelled() || !placeEvent.canBuild()) {
            replacedState.update(true, false);
            if (placeEvent.isCancelled()) {
                event.setUseInteractedBlock(Event.Result.DENY);
                event.setUseItemInHand(Event.Result.DENY);
            }
            return true;
        }

        storageBlockManager.refreshHopperLinks(storageBlock);
        if (player.getGameMode() != GameMode.CREATIVE) {
            if (item.getAmount() <= 1) {
                player.getInventory().setItemInMainHand(null);
            } else {
                item.setAmount(item.getAmount() - 1);
            }
        }
        return true;
    }

    private void refreshNearbyHopperLinks(Block hopperBlock) {
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN)) {
            Block relative = hopperBlock.getRelative(face);
            if (storageBlockManager.get(relative) != null) {
                storageBlockManager.refreshHopperLinks(relative);
            }
        }
    }
}
