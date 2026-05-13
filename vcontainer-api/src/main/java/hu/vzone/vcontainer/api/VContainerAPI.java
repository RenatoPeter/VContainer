package hu.vzone.vcontainer.api;

import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface VContainerAPI {
    void addItem(Player player, ItemStack item);

    void addItem(UUID ownerId, ItemStack item);

    void removeItem(Player player, ItemStack item);

    void removeItem(UUID ownerId, ItemStack item);

    int takeItem(UUID ownerId, ItemStack item, int amount);

    List<ItemStack> getItems(Player player);

    List<ItemStack> getItems(UUID ownerId);

    boolean containsItem(Player player, ItemStack item);

    void clear(Player player);

    void clear(UUID ownerId);

    void openContainer(Player player);

    void openContainer(Player viewer, UUID ownerId, String ownerName);

    void openAdminContainer(Player admin, Player owner);

    void flush();

    int getDirtyContainerCount();

    int getDirtyStorageBlockSaveCount();

    int getDirtyStorageBlockDeleteCount();

    ItemStack createPersonalStorageBlockItem(int amount);

    boolean isPersonalStorageBlockItem(ItemStack item);

    boolean createGlobalStorageBlock(Block block);

    boolean createPersonalStorageBlock(Block block, Player owner);

    boolean removeGlobalStorageBlock(Block block);

    boolean removePersonalStorageBlock(String storageKey, boolean keepBlock);

    boolean removeStorageBlock(String storageKey, boolean keepBlock, String reason);

    boolean isStorageBlock(Block block);

    Optional<StorageBlockInfo> getStorageBlock(Block block);

    Optional<StorageBlockInfo> getStorageBlock(String storageKey);

    Collection<StorageBlockInfo> getStorageBlocks();

    Collection<StorageBlockInfo> getGlobalStorageBlocks();

    Collection<StorageBlockInfo> getPersonalStorageBlocks();

    boolean canAccessStorageBlock(Player player, String storageKey);

    boolean isStorageBlockOwner(Player player, String storageKey);

    boolean canPlacePersonalStorageBlock(Block block, Player owner);

    int getPersonalStorageBlockChunkLimit();

    boolean addStorageBlockMember(String storageKey, UUID memberId);

    boolean removeStorageBlockMember(String storageKey, UUID memberId);

    boolean setStorageBlockMember(String storageKey, UUID memberId, boolean member);

    boolean setStorageBlockOwner(String storageKey, UUID ownerId, String ownerName);

    void refreshHopperLinks(Block storageBlock);

    String getStorageBlockKey(Block block);

    String getStorageBackendType();

    boolean isLocalStorageBackend();

    boolean isRestartRequired();

    String getRestartReason();

    void audit(String action, String target, String detail);

    CompletableFuture<File> exportBackup(String name);

    CompletableFuture<Void> migrate(String targetType);
}
