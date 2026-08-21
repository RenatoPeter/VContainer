package hu.vzone.vcontainer.api.impl;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.api.StorageBlockInfo;
import hu.vzone.vcontainer.api.StorageBlockType;
import hu.vzone.vcontainer.api.VContainerAPI;
import hu.vzone.vcontainer.gui.ContainerGUI;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.managers.StorageBlockManager;
import hu.vzone.vcontainer.managers.StorageBlockManager.StorageBlock;
import hu.vzone.vcontainer.storage.StorageSettings;
import hu.vzone.vcontainer.utils.AdminDataService;
import hu.vzone.vcontainer.utils.AuditLogger;
import hu.vzone.vcontainer.utils.StorageBlockItem;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class VContainerAPIImpl implements VContainerAPI {
    private final VContainer plugin;
    private final ContainerManager manager;
    private final StorageBlockManager storageBlockManager;

    public VContainerAPIImpl(VContainer plugin, ContainerManager manager, StorageBlockManager storageBlockManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.storageBlockManager = storageBlockManager;
    }

    @Override
    public boolean isContainerLoaded(UUID ownerId) {
        return manager.isContainerLoaded(ownerId);
    }

    @Override
    public void addItem(Player player, ItemStack item) {
        manager.addItemToContainer(player, item);
    }

    @Override
    public void addItem(UUID ownerId, ItemStack item) {
        manager.addItemToContainer(ownerId, item);
    }

    @Override
    public void removeItem(Player player, ItemStack item) {
        manager.removeItemFromContainer(player, item);
    }

    @Override
    public void removeItem(UUID ownerId, ItemStack item) {
        manager.removeItemFromContainer(ownerId, item);
    }

    @Override
    public int takeItem(UUID ownerId, ItemStack item, int amount) {
        return manager.takeItemFromContainer(ownerId, item, amount);
    }

    @Override
    public List<ItemStack> getItems(Player player) {
        return manager.getAllItemFromContainer(player);
    }

    @Override
    public List<ItemStack> getItems(UUID ownerId) {
        return manager.getAllItemFromContainer(ownerId);
    }

    @Override
    public boolean containsItem(Player player, ItemStack item) {
        return manager.itemInContainer(player, item);
    }

    @Override
    public void clear(Player player) {
        manager.clearContainer(player);
    }

    @Override
    public void clear(UUID ownerId) {
        manager.clearContainer(ownerId);
    }

    @Override
    public void openContainer(Player player) {
        ContainerGUI.openContainer(player, manager, 1);
    }

    @Override
    public void openContainer(Player viewer, UUID ownerId, String ownerName) {
        ContainerGUI.openContainerForStorage(viewer, ownerId, ownerName, manager, storageBlockManager, null);
    }

    @Override
    public void openAdminContainer(Player admin, Player owner) {
        ContainerGUI.openContainerForAdmin(admin, owner, manager, 1);
    }

    @Override
    public void flush() {
        manager.flushSync();
        storageBlockManager.flushSync();
    }

    @Override
    public int getDirtyContainerCount() {
        return manager.dirtyCount();
    }

    @Override
    public int getDirtyStorageBlockSaveCount() {
        return storageBlockManager.dirtySaveCount();
    }

    @Override
    public int getDirtyStorageBlockDeleteCount() {
        return storageBlockManager.dirtyDeleteCount();
    }

    @Override
    public ItemStack createPersonalStorageBlockItem(int amount) {
        return StorageBlockItem.build(plugin, amount);
    }

    @Override
    public boolean isPersonalStorageBlockItem(ItemStack item) {
        return StorageBlockItem.isStorageBlockItem(plugin, item);
    }

    @Override
    public boolean createGlobalStorageBlock(Block block) {
        return storageBlockManager.add(block);
    }

    @Override
    public boolean createPersonalStorageBlock(Block block, Player owner) {
        if (!storageBlockManager.canPlacePersonal(block, owner)) return false;
        return storageBlockManager.addPersonal(block, owner);
    }

    @Override
    public boolean removeGlobalStorageBlock(Block block) {
        return storageBlockManager.removeGlobal(block);
    }

    @Override
    public boolean removePersonalStorageBlock(String storageKey, boolean keepBlock) {
        return storageBlockManager.removePersonal(storageKey, keepBlock);
    }

    @Override
    public boolean removeStorageBlock(String storageKey, boolean keepBlock, String reason) {
        boolean removed = storageBlockManager.removeByKey(storageKey, keepBlock);
        if (removed) AuditLogger.logSystem("api-storage-block-remove", storageKey, reason);
        return removed;
    }

    @Override
    public boolean isStorageBlock(Block block) {
        return storageBlockManager.isStorageBlock(block);
    }

    @Override
    public Optional<StorageBlockInfo> getStorageBlock(Block block) {
        return Optional.ofNullable(toInfo(storageBlockManager.get(block)));
    }

    @Override
    public Optional<StorageBlockInfo> getStorageBlock(String storageKey) {
        return Optional.ofNullable(toInfo(storageBlockManager.get(storageKey)));
    }

    @Override
    public Collection<StorageBlockInfo> getStorageBlocks() {
        return storageBlockManager.getStorageBlocks().stream().map(this::toInfo).toList();
    }

    @Override
    public Collection<StorageBlockInfo> getGlobalStorageBlocks() {
        return storageBlockManager.getGlobalStorageBlocks().stream().map(this::toInfo).toList();
    }

    @Override
    public Collection<StorageBlockInfo> getPersonalStorageBlocks() {
        return storageBlockManager.getPersonalStorageBlocks().stream().map(this::toInfo).toList();
    }

    @Override
    public boolean canAccessStorageBlock(Player player, String storageKey) {
        return storageBlockManager.canAccess(player, storageBlockManager.get(storageKey));
    }

    @Override
    public boolean isStorageBlockOwner(Player player, String storageKey) {
        return storageBlockManager.isOwner(player, storageBlockManager.get(storageKey));
    }

    @Override
    public boolean canPlacePersonalStorageBlock(Block block, Player owner) {
        return storageBlockManager.canPlacePersonal(block, owner);
    }

    @Override
    public int getPersonalStorageBlockChunkLimit() {
        return storageBlockManager.personalChunkLimit();
    }

    @Override
    public boolean addStorageBlockMember(String storageKey, UUID memberId) {
        return storageBlockManager.setMember(storageKey, memberId, true);
    }

    @Override
    public boolean removeStorageBlockMember(String storageKey, UUID memberId) {
        return storageBlockManager.setMember(storageKey, memberId, false);
    }

    @Override
    public boolean setStorageBlockMember(String storageKey, UUID memberId, boolean member) {
        return storageBlockManager.setMember(storageKey, memberId, member);
    }

    @Override
    public boolean setStorageBlockOwner(String storageKey, UUID ownerId, String ownerName) {
        return storageBlockManager.setOwner(storageKey, ownerId, ownerName);
    }

    @Override
    public void refreshHopperLinks(Block storageBlock) {
        storageBlockManager.refreshHopperLinks(storageBlock);
    }

    @Override
    public String getStorageBlockKey(Block block) {
        return storageBlockManager.key(block);
    }

    @Override
    public String getStorageBackendType() {
        return StorageSettings.from(plugin).type().name();
    }

    @Override
    public boolean isLocalStorageBackend() {
        return plugin.isLocalStorageBackend();
    }

    @Override
    public boolean isRestartRequired() {
        return plugin.isRestartRequired();
    }

    @Override
    public String getRestartReason() {
        return plugin.getRestartReason();
    }

    @Override
    public void audit(String action, String target, String detail) {
        AuditLogger.logSystem(action, target, detail);
    }

    @Override
    public CompletableFuture<File> exportBackup(String name) {
        return runAsync(() -> {
            try {
                flush();
                return AdminDataService.exportBackup(plugin, name);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> migrate(String targetType) {
        return runAsync(() -> {
            try {
                StorageSettings.StorageType type = StorageSettings.StorageType.valueOf(targetType.toUpperCase(java.util.Locale.ROOT));
                AdminDataService.migrate(plugin, manager, storageBlockManager, type);
                return null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private <T> CompletableFuture<T> runAsync(java.util.concurrent.Callable<T> callable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                future.complete(callable.call());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private StorageBlockInfo toInfo(StorageBlock storageBlock) {
        if (storageBlock == null) return null;
        return new StorageBlockInfo(
                storageBlock.id(),
                storageBlock.key(),
                StorageBlockType.valueOf(storageBlock.type().name()),
                storageBlock.ownerId(),
                storageBlock.ownerName(),
                Set.copyOf(new HashSet<>(storageBlock.members()))
        );
    }
}
