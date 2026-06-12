package hu.vzone.vcontainer.managers;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.api.events.StorageBlockHopperTransferEvent;
import hu.vzone.vcontainer.storage.StorageSettings;
import hu.vzone.vcontainer.utils.AuditLogger;
import hu.vzone.vcontainer.utils.ItemUtils;
import hu.vzone.vcontainer.utils.PlaceholderHook;
import hu.vzone.vcontainer.utils.PermissionUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Hopper;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class StorageBlockManager {
    private static final long AUTO_SAVE_TICKS = 20L * 60L * 2L;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final String HOLOGRAM_TAG = "vcontainer_storage_hologram";
    private static final BlockFace[] HOPPER_INPUT_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP
    };

    private final VContainer plugin;
    private final ContainerManager containerManager;
    private final File legacyFile;
    private final File legacyGlobalFile;
    private final File legacyPersonalFile;
    private final File globalDirectory;
    private final File personalDirectory;
    private final boolean localStorage;
    private final StorageSettings storageSettings;
    private HikariDataSource sqlDataSource;
    private String globalTable;
    private String personalTable;
    private final Map<String, UUID> holograms = new HashMap<>();
    private final Map<String, StorageBlock> globalBlocks = new HashMap<>();
    private final Map<String, StorageBlock> personalBlocks = new HashMap<>();
    private final Map<String, Set<String>> personalBlocksByChunk = new HashMap<>();
    private final Map<String, HopperLinks> hopperLinks = new HashMap<>();
    private final Set<String> activeHopperBlocks = new HashSet<>();
    private final List<String> activeHopperBlockKeys = new ArrayList<>();
    private final Map<UUID, DirtyBlockSave> dirtyBlockSaves = new HashMap<>();
    private final Map<UUID, DirtyBlockDelete> dirtyBlockDeletes = new HashMap<>();
    private final Object blockSaveLock = new Object();
    private long blockMutationVersion;
    private BukkitTask hopperTask;
    private BukkitTask autoSaveTask;
    private BukkitTask hologramRefreshTask;
    private int hopperCursor;
    private volatile boolean persistenceSuspended;

    public StorageBlockManager(VContainer plugin, ContainerManager containerManager) {
        this.plugin = plugin;
        this.containerManager = containerManager;
        this.legacyFile = new File(plugin.getDataFolder(), "storage_blocks.yml");
        this.legacyGlobalFile = new File(plugin.getDataFolder(), "global_storage_blocks.yml");
        this.legacyPersonalFile = new File(plugin.getDataFolder(), "personal_storage_blocks.yml");
        this.storageSettings = StorageSettings.from(plugin);
        this.localStorage = storageSettings.type() == StorageSettings.StorageType.LOCAL;
        File storageDirectory = plugin.getStorageFolder();
        this.globalDirectory = new File(storageDirectory, "global_storage_blocks");
        this.personalDirectory = new File(storageDirectory, "personal_storage_blocks");
        if (!localStorage) initSqlStorage();
        load();
        startAutoSaveTask();
        startHopperTask();
    }

    public boolean add(Block block) {
        String key = key(block.getLocation());
        if (globalBlocks.containsKey(key) || personalBlocks.containsKey(key)) return false;

        StorageBlock storageBlock = StorageBlock.global(UUID.randomUUID(), key);
        globalBlocks.put(key, storageBlock);
        spawnHologram(block.getLocation());
        markBlockDirty(storageBlock);
        return true;
    }

    public boolean addPersonal(Block block, Player owner) {
        String key = key(block.getLocation());
        if (globalBlocks.containsKey(key) || personalBlocks.containsKey(key)) return false;

        StorageBlock storageBlock = StorageBlock.personal(UUID.randomUUID(), key, owner.getUniqueId(), owner.getName());
        personalBlocks.put(key, storageBlock);
        registerPersonalBlock(storageBlock);
        refreshHopperLinks(storageBlock);
        spawnHologram(block.getLocation());
        markBlockDirty(storageBlock);
        return true;
    }

    public boolean removeGlobal(Block block) {
        if (block == null) return false;
        String key = key(block.getLocation());
        StorageBlock removed = globalBlocks.remove(key);
        if (removed == null) return false;

        removeHologram(block.getLocation());
        markBlockDeleted(removed);
        return true;
    }

    public boolean removePersonal(String key, boolean keepBlock) {
        StorageBlock removed = personalBlocks.remove(key);
        if (removed == null) return false;
        unregisterPersonalBlock(removed);
        hopperLinks.remove(key);
        setHopperActive(key, false);

        Location location = locationFromKey(key);
        if (location != null) {
            removeHologram(location);
            if (!keepBlock && location.getWorld() != null) {
                location.getBlock().setType(Material.AIR);
            }
        }

        markBlockDeleted(removed);
        return true;
    }

    public boolean isStorageBlock(Block block) {
        return get(block) != null;
    }

    public StorageBlock get(Block block) {
        if (block == null) return null;
        String key = key(block.getLocation());
        StorageBlock personal = personalBlocks.get(key);
        return personal != null ? personal : globalBlocks.get(key);
    }

    public StorageBlock get(String key) {
        StorageBlock personal = personalBlocks.get(key);
        return personal != null ? personal : globalBlocks.get(key);
    }

    public Location locationOf(String key) {
        return locationFromKey(key);
    }

    public boolean removeByKey(String key, boolean keepBlock) {
        StorageBlock storageBlock = get(key);
        if (storageBlock == null) return false;
        if (storageBlock.type() == StorageType.PERSONAL) {
            return removePersonal(key, keepBlock);
        }

        Location location = locationFromKey(key);
        if (location == null) return false;
        return removeGlobal(location.getBlock());
    }

    public boolean setOwner(String key, UUID ownerId, String ownerName) {
        StorageBlock storageBlock = personalBlocks.get(key);
        if (storageBlock == null || ownerId == null || ownerName == null || ownerName.isBlank()) return false;

        StorageBlock updated = new StorageBlock(storageBlock.id(), storageBlock.key(), storageBlock.type(), ownerId, ownerName, storageBlock.members());
        personalBlocks.put(key, updated);
        markBlockDirty(updated);
        reloadHolograms();
        return true;
    }

    public boolean canAccess(Player player, StorageBlock storageBlock) {
        if (storageBlock == null) return false;
        if (storageBlock.type() == StorageType.GLOBAL) return true;
        if (player.isOp()) return true;
        return storageBlock.ownerId().equals(player.getUniqueId()) || storageBlock.members().contains(player.getUniqueId());
    }

    public boolean isOwner(Player player, StorageBlock storageBlock) {
        return storageBlock != null && storageBlock.type() == StorageType.PERSONAL && storageBlock.ownerId().equals(player.getUniqueId());
    }

    public boolean canManage(Player player, StorageBlock storageBlock) {
        if (player == null || storageBlock == null) return false;
        if (storageBlock.type() != StorageType.PERSONAL) return false;
        return player.isOp() || storageBlock.ownerId().equals(player.getUniqueId());
    }

    public boolean canPlacePersonal(Block block, Player owner) {
        if (PermissionUtils.has(owner, "vcontainer.block.limit.bypass")) return true;

        int limit = personalChunkLimit();
        if (limit <= 0) return false;
        return countPersonalBlocksInChunk(block, owner.getUniqueId()) < limit;
    }

    public int personalChunkLimit() {
        return Math.max(0, plugin.getConfig().getInt("storage-block.personal.chunk-limit", 4));
    }

    public void toggleMember(String key, Player player) {
        StorageBlock storageBlock = personalBlocks.get(key);
        if (storageBlock == null) return;

        if (!storageBlock.members().remove(player.getUniqueId())) {
            storageBlock.members().add(player.getUniqueId());
        }
        markBlockDirty(storageBlock);
    }

    public boolean setMember(String key, UUID memberId, boolean member) {
        StorageBlock storageBlock = personalBlocks.get(key);
        if (storageBlock == null || memberId == null || storageBlock.ownerId().equals(memberId)) return false;

        boolean changed = member ? storageBlock.members().add(memberId) : storageBlock.members().remove(memberId);
        if (changed) markBlockDirty(storageBlock);
        return changed;
    }

    public Collection<StorageBlock> getStorageBlocks() {
        List<StorageBlock> blocks = new ArrayList<>(globalBlocks.values());
        blocks.addAll(personalBlocks.values());
        return Collections.unmodifiableList(blocks);
    }

    public Collection<StorageBlock> getGlobalStorageBlocks() {
        return Collections.unmodifiableCollection(new ArrayList<>(globalBlocks.values()));
    }

    public Collection<StorageBlock> getPersonalStorageBlocks() {
        return Collections.unmodifiableCollection(new ArrayList<>(personalBlocks.values()));
    }

    public String key(Block block) {
        return key(block.getLocation());
    }

    public void refreshHopperLinks(Block storageBlock) {
        StorageBlock block = get(storageBlock);
        if (block != null && block.type() == StorageType.PERSONAL) {
            refreshHopperLinks(block);
        }
    }

    private int countPersonalBlocksInChunk(Block block, UUID ownerId) {
        if (block == null || block.getWorld() == null) return 0;

        String worldName = block.getWorld().getName();
        int chunkX = block.getX() >> 4;
        int chunkZ = block.getZ() >> 4;
        int count = 0;

        Set<String> chunkBlocks = personalBlocksByChunk.get(chunkKey(worldName, chunkX, chunkZ));
        if (chunkBlocks == null || chunkBlocks.isEmpty()) return 0;

        for (String storageKey : chunkBlocks) {
            StorageBlock storageBlock = personalBlocks.get(storageKey);
            if (storageBlock == null) continue;
            if (!ownerId.equals(storageBlock.ownerId())) continue;
            count++;
        }
        return count;
    }

    private void registerPersonalBlock(StorageBlock storageBlock) {
        String chunkKey = chunkKey(storageBlock.key());
        if (chunkKey == null) return;
        personalBlocksByChunk.computeIfAbsent(chunkKey, ignored -> new HashSet<>()).add(storageBlock.key());
    }

    private void unregisterPersonalBlock(StorageBlock storageBlock) {
        String chunkKey = chunkKey(storageBlock.key());
        if (chunkKey == null) return;

        Set<String> blocks = personalBlocksByChunk.get(chunkKey);
        if (blocks == null) return;
        blocks.remove(storageBlock.key());
        if (blocks.isEmpty()) {
            personalBlocksByChunk.remove(chunkKey);
        }
    }

    private HopperLinks refreshHopperLinks(StorageBlock storageBlock) {
        Location location = locationFromKey(storageBlock.key());
        if (location == null || location.getWorld() == null || !location.isChunkLoaded()) {
            HopperLinks empty = new HopperLinks(List.of(), null);
            hopperLinks.put(storageBlock.key(), empty);
            return empty;
        }

        List<String> inputKeys = new ArrayList<>();
        Block storageBlockBlock = location.getBlock();
        for (BlockFace face : HOPPER_INPUT_FACES) {
            Block hopperBlock = storageBlockBlock.getRelative(face);
            if (isInputHopper(hopperBlock, storageBlockBlock)) {
                inputKeys.add(key(hopperBlock.getLocation()));
            }
        }

        Block outputHopper = storageBlockBlock.getRelative(BlockFace.DOWN);
        String outputKey = outputHopper.getType() == Material.HOPPER ? key(outputHopper.getLocation()) : null;
        HopperLinks links = new HopperLinks(inputKeys, outputKey);
        hopperLinks.put(storageBlock.key(), links);
        setHopperActive(storageBlock.key(), links.hasAny());
        return links;
    }

    private void setHopperActive(String key, boolean active) {
        if (active) {
            if (activeHopperBlocks.add(key)) {
                activeHopperBlockKeys.add(key);
            }
            return;
        }

        if (activeHopperBlocks.remove(key)) {
            activeHopperBlockKeys.remove(key);
            if (hopperCursor >= activeHopperBlockKeys.size()) hopperCursor = 0;
        }
    }

    private boolean isInputHopper(Block hopperBlock, Block storageBlock) {
        if (hopperBlock.getType() != Material.HOPPER || !(hopperBlock.getBlockData() instanceof Directional directional)) return false;
        return hopperBlock.getRelative(directional.getFacing()).equals(storageBlock);
    }

    private String chunkKey(String storageKey) {
        String[] parts = storageKey.split(",");
        if (parts.length != 4) return null;
        try {
            return chunkKey(parts[0], Integer.parseInt(parts[1]) >> 4, Integer.parseInt(parts[3]) >> 4);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String chunkKey(String worldName, int chunkX, int chunkZ) {
        return worldName + "," + chunkX + "," + chunkZ;
    }

    public void reloadHolograms() {
        if (hologramRefreshTask != null) {
            hologramRefreshTask.cancel();
            hologramRefreshTask = null;
        }
        removeAllHolograms();
        if (!plugin.getConfig().getBoolean("storage-block.hologram.enabled", true)) return;

        List<Location> pending = new ArrayList<>();
        for (String key : globalBlocks.keySet()) {
            Location location = locationFromKey(key);
            if (location != null && location.isChunkLoaded()) pending.add(location);
        }
        for (String key : personalBlocks.keySet()) {
            Location location = locationFromKey(key);
            if (location != null && location.isChunkLoaded()) pending.add(location);
        }
        if (pending.isEmpty()) return;

        int batchSize = Math.max(1, plugin.getConfig().getInt("storage-block.hologram.refresh-batch-size", 50));
        hologramRefreshTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            private int index;

            @Override
            public void run() {
                int processed = 0;
                while (index < pending.size() && processed < batchSize) {
                    spawnHologram(pending.get(index++));
                    processed++;
                }
                if (index >= pending.size() && hologramRefreshTask != null) {
                    hologramRefreshTask.cancel();
                    hologramRefreshTask = null;
                }
            }
        }, 0L, 1L);
    }

    public void reload() {
        if (hopperTask != null) hopperTask.cancel();
        hopperLinks.clear();
        activeHopperBlocks.clear();
        activeHopperBlockKeys.clear();
        hopperCursor = 0;
        for (StorageBlock storageBlock : personalBlocks.values()) {
            refreshHopperLinks(storageBlock);
        }
        startHopperTask();
        reloadHolograms();
    }

    public void removeAllHolograms() {
        if (hologramRefreshTask != null) {
            hologramRefreshTask.cancel();
            hologramRefreshTask = null;
        }
        for (String key : new ArrayList<>(holograms.keySet())) {
            Location location = locationFromKey(key);
            if (location != null) {
                removeHologram(location);
            } else {
                holograms.remove(key);
            }
        }
        purgeOrphanHolograms();
        holograms.clear();
    }

    public void handleChunkLoad(World world, int chunkX, int chunkZ) {
        updateChunkHolograms(world, chunkX, chunkZ, true);
    }

    public void handleChunkUnload(World world, int chunkX, int chunkZ) {
        updateChunkHolograms(world, chunkX, chunkZ, false);
    }

    public void shutdown() {
        if (hopperTask != null) hopperTask.cancel();
        if (autoSaveTask != null) autoSaveTask.cancel();
        if (hologramRefreshTask != null) hologramRefreshTask.cancel();
        if (!persistenceSuspended) flushDirtyBlocksSync();
        removeAllHolograms();
        if (sqlDataSource != null) sqlDataSource.close();
    }

    public void flushSync() {
        if (persistenceSuspended) return;
        flushDirtyBlocksSync();
    }

    public void setPersistenceSuspended(boolean suspended) {
        persistenceSuspended = suspended;
    }

    public boolean isPersistenceSuspended() {
        return persistenceSuspended;
    }

    public int dirtySaveCount() {
        synchronized (blockSaveLock) {
            return dirtyBlockSaves.size();
        }
    }

    public int dirtyDeleteCount() {
        synchronized (blockSaveLock) {
            return dirtyBlockDeletes.size();
        }
    }

    private void load() {
        if (localStorage) {
            ensureStorageDirectories();
            loadJsonBlocks(globalDirectory, StorageType.GLOBAL);
            loadJsonBlocks(personalDirectory, StorageType.PERSONAL);
        } else {
            loadSqlBlocks();
        }
        loadLegacyIfPresent();
        loadGlobal();
        loadPersonal();
        for (StorageBlock storageBlock : personalBlocks.values()) {
            refreshHopperLinks(storageBlock);
        }
        reloadHolograms();
    }

    private void loadLegacyIfPresent() {
        if (!legacyFile.exists()) return;

        FileConfiguration config = YamlConfiguration.loadConfiguration(legacyFile);
        if (config.isList("blocks")) {
            for (String key : config.getStringList("blocks")) {
                if (!globalBlocks.containsKey(key) && !personalBlocks.containsKey(key)) {
                    globalBlocks.put(key, StorageBlock.global(UUID.randomUUID(), key));
                }
            }
            saveAllBlocks();
            return;
        }

        ConfigurationSection section = config.getConfigurationSection("blocks");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String path = "blocks." + key + ".";
            StorageType type = StorageType.valueOf(config.getString(path + "type", "GLOBAL"));
            if (globalBlocks.containsKey(key) || personalBlocks.containsKey(key)) continue;
            if (type == StorageType.PERSONAL) {
                UUID ownerId = UUID.fromString(config.getString(path + "owner-id"));
                String ownerName = config.getString(path + "owner-name", "Unknown");
                Set<UUID> members = new HashSet<>();
                for (String member : config.getStringList(path + "members")) members.add(UUID.fromString(member));
                StorageBlock storageBlock = new StorageBlock(UUID.randomUUID(), key, type, ownerId, ownerName, members);
                personalBlocks.put(key, storageBlock);
                registerPersonalBlock(storageBlock);
            } else {
                globalBlocks.put(key, StorageBlock.global(UUID.randomUUID(), key));
            }
        }
        saveAllBlocks();
    }

    private void loadGlobal() {
        if (!legacyGlobalFile.exists()) return;
        FileConfiguration config = YamlConfiguration.loadConfiguration(legacyGlobalFile);
        for (String key : config.getStringList("blocks")) {
            if (!globalBlocks.containsKey(key) && !personalBlocks.containsKey(key)) {
                globalBlocks.put(key, StorageBlock.global(UUID.randomUUID(), key));
            }
        }
        saveAllBlocks();
    }

    private void loadPersonal() {
        if (!legacyPersonalFile.exists()) return;
        FileConfiguration config = YamlConfiguration.loadConfiguration(legacyPersonalFile);
        ConfigurationSection section = config.getConfigurationSection("blocks");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            if (globalBlocks.containsKey(key) || personalBlocks.containsKey(key)) continue;
            String path = "blocks." + key + ".";
            UUID ownerId = UUID.fromString(config.getString(path + "owner-id"));
            String ownerName = config.getString(path + "owner-name", "Unknown");
            Set<UUID> members = new HashSet<>();
            for (String member : config.getStringList(path + "members")) members.add(UUID.fromString(member));
            StorageBlock storageBlock = new StorageBlock(UUID.randomUUID(), key, StorageType.PERSONAL, ownerId, ownerName, members);
            personalBlocks.put(key, storageBlock);
            registerPersonalBlock(storageBlock);
        }
        saveAllBlocks();
    }

    private void ensureStorageDirectories() {
        if (!localStorage) return;
        if (!globalDirectory.exists() && !globalDirectory.mkdirs()) {
            plugin.getLogger().warning("Could not create global storage block directory: " + globalDirectory.getAbsolutePath());
        }
        if (!personalDirectory.exists() && !personalDirectory.mkdirs()) {
            plugin.getLogger().warning("Could not create personal storage block directory: " + personalDirectory.getAbsolutePath());
        }
    }

    private void initSqlStorage() {
        globalTable = storageSettings.prefix() + "global_storage_blocks";
        personalTable = storageSettings.prefix() + "personal_storage_blocks";

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(storageSettings.buildJdbcUrl(plugin.getDataFolder()));
        config.setMaximumPoolSize(storageSettings.poolSize());
        config.setPoolName("VContainer-Blocks-" + storageSettings.type().name());

        String driverClass = storageSettings.resolvedDriverClass();
        if (!driverClass.isBlank()) config.setDriverClassName(driverClass);
        if (storageSettings.type() != StorageSettings.StorageType.H2) {
            config.setUsername(storageSettings.username());
            config.setPassword(storageSettings.password());
        }

        sqlDataSource = new HikariDataSource(config);
        createSqlTables();
    }

    private void createSqlTables() {
        String globalSql = "CREATE TABLE IF NOT EXISTS " + globalTable + " ("
                + "id VARCHAR(36) NOT NULL PRIMARY KEY,"
                + "location_key VARCHAR(255) NOT NULL UNIQUE"
                + ")";
        String personalSql = "CREATE TABLE IF NOT EXISTS " + personalTable + " ("
                + "id VARCHAR(36) NOT NULL PRIMARY KEY,"
                + "location_key VARCHAR(255) NOT NULL UNIQUE,"
                + "owner_uuid VARCHAR(36) NOT NULL,"
                + "owner_name VARCHAR(64) NOT NULL,"
                + "members TEXT"
                + ")";

        try (Connection connection = sqlDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(globalSql);
            statement.executeUpdate(personalSql);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to create SQL storage block tables: " + e.getMessage());
        }
    }

    private void loadSqlBlocks() {
        loadSqlGlobalBlocks();
        loadSqlPersonalBlocks();
    }

    private void loadSqlGlobalBlocks() {
        String sql = "SELECT id, location_key FROM " + globalTable;
        try (Connection connection = sqlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID id = UUID.fromString(result.getString("id"));
                String key = result.getString("location_key");
                globalBlocks.put(key, StorageBlock.global(id, key));
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load SQL global storage blocks: " + e.getMessage());
        }
    }

    private void loadSqlPersonalBlocks() {
        String sql = "SELECT id, location_key, owner_uuid, owner_name, members FROM " + personalTable;
        try (Connection connection = sqlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID id = UUID.fromString(result.getString("id"));
                String key = result.getString("location_key");
                UUID ownerId = UUID.fromString(result.getString("owner_uuid"));
                String ownerName = result.getString("owner_name");
                Set<UUID> members = parseMembers(result.getString("members"));
                StorageBlock storageBlock = new StorageBlock(id, key, StorageType.PERSONAL, ownerId, ownerName, members);
                personalBlocks.put(key, storageBlock);
                registerPersonalBlock(storageBlock);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load SQL personal storage blocks: " + e.getMessage());
        }
    }

    private void loadJsonBlocks(File directory, StorageType type) {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;

        for (File file : files) {
            try {
                StorageBlockData data = plugin.getGson().fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), StorageBlockData.class);
                StorageBlock storageBlock = fromData(data, type);
                if (storageBlock == null) continue;

                if (storageBlock.type() == StorageType.PERSONAL) {
                    personalBlocks.put(storageBlock.key(), storageBlock);
                    registerPersonalBlock(storageBlock);
                    refreshHopperLinks(storageBlock);
                } else {
                    globalBlocks.put(storageBlock.key(), storageBlock);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load storage block " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    private StorageBlock fromData(StorageBlockData data, StorageType fallbackType) {
        if (data == null || data.key == null || data.key.isBlank()) return null;

        UUID id = parseUuid(data.id, UUID.randomUUID());
        StorageType type = data.type == null ? fallbackType : StorageType.valueOf(data.type);
        UUID ownerId = data.ownerId == null || data.ownerId.isBlank() ? null : UUID.fromString(data.ownerId);
        Set<UUID> members = new HashSet<>();
        if (data.members != null) {
            for (String member : data.members) {
                members.add(UUID.fromString(member));
            }
        }
        return new StorageBlock(id, data.key, type, ownerId, data.ownerName, members);
    }

    private void saveAllBlocks() {
        for (StorageBlock storageBlock : globalBlocks.values()) markBlockDirty(storageBlock);
        for (StorageBlock storageBlock : personalBlocks.values()) markBlockDirty(storageBlock);
    }

    private void markBlockDirty(StorageBlock storageBlock) {
        synchronized (blockSaveLock) {
            dirtyBlockSaves.put(storageBlock.id(), new DirtyBlockSave(toData(storageBlock), ++blockMutationVersion));
            dirtyBlockDeletes.remove(storageBlock.id());
        }
    }

    private void markBlockDeleted(StorageBlock storageBlock) {
        synchronized (blockSaveLock) {
            dirtyBlockSaves.remove(storageBlock.id());
            dirtyBlockDeletes.put(storageBlock.id(), new DirtyBlockDelete(storageBlock.type(), ++blockMutationVersion));
        }
    }

    private void flushDirtyBlocksSync() {
        if (persistenceSuspended) return;
        Map<UUID, DirtyBlockSave> saves;
        Map<UUID, DirtyBlockDelete> deletes;
        synchronized (blockSaveLock) {
            saves = new HashMap<>(dirtyBlockSaves);
            deletes = new HashMap<>(dirtyBlockDeletes);
        }

        for (Map.Entry<UUID, DirtyBlockDelete> entry : deletes.entrySet()) {
            if (deleteBlockData(entry.getKey(), entry.getValue().type())) {
                markBlockDeleteSaved(entry.getKey(), entry.getValue().version());
            }
        }

        for (Map.Entry<UUID, DirtyBlockSave> entry : saves.entrySet()) {
            if (saveBlockData(entry.getValue().data())) {
                markBlockSaveSaved(entry.getKey(), entry.getValue().version());
            }
        }
    }

    private void markBlockSaveSaved(UUID id, long savedVersion) {
        synchronized (blockSaveLock) {
            DirtyBlockSave current = dirtyBlockSaves.get(id);
            if (current != null && current.version() == savedVersion) {
                dirtyBlockSaves.remove(id);
            }
        }
    }

    private void markBlockDeleteSaved(UUID id, long savedVersion) {
        synchronized (blockSaveLock) {
            DirtyBlockDelete current = dirtyBlockDeletes.get(id);
            if (current != null && current.version() == savedVersion) {
                dirtyBlockDeletes.remove(id);
            }
        }
    }

    private boolean saveBlockData(StorageBlockData data) {
        if (!localStorage) {
            return saveSqlBlock(data);
        }
        ensureStorageDirectories();
        File file = fileFor(data);
        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            Files.writeString(tempFile.toPath(), plugin.getGson().toJson(data), StandardCharsets.UTF_8);
            try {
                Files.move(tempFile.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempFile.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tempFile.toPath());
            } catch (IOException ignored) {
            }
            plugin.getLogger().severe("Failed to save storage block " + data.id + ": " + e.getMessage());
            return false;
        }
    }

    private boolean deleteBlockData(UUID id, StorageType type) {
        if (!localStorage) {
            return deleteSqlBlock(id, type);
        }
        try {
            Files.deleteIfExists(fileFor(id, type).toPath());
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to delete storage block file " + id + ": " + e.getMessage());
            return false;
        }
    }

    private boolean saveSqlBlock(StorageBlockData data) {
        StorageType type = StorageType.valueOf(data.type);
        String table = type == StorageType.PERSONAL ? personalTable : globalTable;
        String updateSql = type == StorageType.PERSONAL
                ? "UPDATE " + table + " SET location_key = ?, owner_uuid = ?, owner_name = ?, members = ? WHERE id = ?"
                : "UPDATE " + table + " SET location_key = ? WHERE id = ?";
        String insertSql = type == StorageType.PERSONAL
                ? "INSERT INTO " + table + " (id, location_key, owner_uuid, owner_name, members) VALUES (?, ?, ?, ?, ?)"
                : "INSERT INTO " + table + " (id, location_key) VALUES (?, ?)";

        try (Connection connection = sqlDataSource.getConnection()) {
            connection.setAutoCommit(false);

            try {
                int updated;
                try (PreparedStatement update = connection.prepareStatement(updateSql)) {
                    if (type == StorageType.PERSONAL) {
                        update.setString(1, data.key);
                        update.setString(2, data.ownerId);
                        update.setString(3, data.ownerName);
                        update.setString(4, plugin.getGson().toJson(data.members));
                        update.setString(5, data.id);
                    } else {
                        update.setString(1, data.key);
                        update.setString(2, data.id);
                    }
                    updated = update.executeUpdate();
                }

                if (updated == 0) {
                    try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                        insert.setString(1, data.id);
                        insert.setString(2, data.key);
                        if (type == StorageType.PERSONAL) {
                            insert.setString(3, data.ownerId);
                            insert.setString(4, data.ownerName);
                            insert.setString(5, plugin.getGson().toJson(data.members));
                        }
                        insert.executeUpdate();
                    }
                }

                connection.commit();
                return true;
            } catch (Exception e) {
                rollback(connection, "storage block save " + data.id);
                plugin.getLogger().severe("Failed to save SQL storage block " + data.id + ": " + e.getMessage());
                return false;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to open SQL connection for storage block save " + data.id + ": " + e.getMessage());
            return false;
        }
    }

    private boolean deleteSqlBlock(UUID id, StorageType type) {
        String table = type == StorageType.PERSONAL ? personalTable : globalTable;
        String sql = "DELETE FROM " + table + " WHERE id = ?";
        try (Connection connection = sqlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            statement.executeUpdate();
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to delete SQL storage block " + id + ": " + e.getMessage());
            return false;
        }
    }

    private void rollback(Connection connection, String action) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to rollback SQL " + action + ": " + e.getMessage());
        }
    }

    private File fileFor(StorageBlockData data) {
        return fileFor(UUID.fromString(data.id), StorageType.valueOf(data.type));
    }

    private File fileFor(UUID id, StorageType type) {
        File directory = type == StorageType.PERSONAL ? personalDirectory : globalDirectory;
        return new File(directory, id + ".json");
    }

    private StorageBlockData toData(StorageBlock storageBlock) {
        StorageBlockData data = new StorageBlockData();
        data.id = storageBlock.id().toString();
        data.key = storageBlock.key();
        data.type = storageBlock.type().name();
        data.ownerId = storageBlock.ownerId() == null ? null : storageBlock.ownerId().toString();
        data.ownerName = storageBlock.ownerName();
        data.members = storageBlock.members().stream().map(UUID::toString).sorted().toList();
        return data;
    }

    private Set<UUID> parseMembers(String raw) {
        Set<UUID> members = new HashSet<>();
        if (raw == null || raw.isBlank()) return members;

        try {
            String[] values = plugin.getGson().fromJson(raw, String[].class);
            if (values == null) return members;
            for (String value : values) {
                members.add(UUID.fromString(value));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to parse storage block members: " + e.getMessage());
        }
        return members;
    }

    private UUID parseUuid(String value, UUID fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private void startHopperTask() {
        long interval = Math.max(1L, plugin.getConfig().getLong("storage-block.hoppers.interval-ticks", 8L));
        hopperTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickHoppers, interval, interval);
    }

    private void startAutoSaveTask() {
        autoSaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::flushDirtyBlocksSync,
                AUTO_SAVE_TICKS,
                AUTO_SAVE_TICKS
        );
    }

    private void tickHoppers() {
        if (!plugin.getConfig().getBoolean("storage-block.hoppers.enabled", true)) return;
        if (activeHopperBlockKeys.isEmpty()) return;

        int maxPerTick = Math.max(1, plugin.getConfig().getInt("storage-block.hoppers.blocks-per-tick", 64));
        int processed = Math.min(maxPerTick, activeHopperBlockKeys.size());

        for (int i = 0; i < processed; i++) {
            if (hopperCursor >= activeHopperBlockKeys.size()) hopperCursor = 0;
            String key = activeHopperBlockKeys.get(hopperCursor++);
            StorageBlock storageBlock = personalBlocks.get(key);
            if (storageBlock == null) {
                setHopperActive(key, false);
                continue;
            }
            Location location = locationFromKey(storageBlock.key());
            if (location == null || location.getWorld() == null || !location.isChunkLoaded()) continue;

            HopperLinks links = hopperLinks.get(storageBlock.key());
            if (links == null || !links.isStillValid(location)) links = refreshHopperLinks(storageBlock);

            if (plugin.getConfig().getBoolean("storage-block.hoppers.input", true)) tickInputHoppers(links, storageBlock);
            if (plugin.getConfig().getBoolean("storage-block.hoppers.output", true)) tickOutputHopper(links, storageBlock);
        }
    }

    private void tickInputHoppers(HopperLinks links, StorageBlock storageBlock) {
        for (String hopperKey : links.inputHopperKeys()) {
            Location hopperLocation = locationFromKey(hopperKey);
            if (hopperLocation == null || hopperLocation.getWorld() == null || !hopperLocation.isChunkLoaded()) continue;
            moveOneFromHopperToContainer(hopperLocation.getBlock(), storageBlock.ownerId());
        }
    }

    private void tickOutputHopper(HopperLinks links, StorageBlock storageBlock) {
        if (links.outputHopperKey() == null) return;
        Location hopperLocation = locationFromKey(links.outputHopperKey());
        if (hopperLocation == null || hopperLocation.getWorld() == null || !hopperLocation.isChunkLoaded()) return;
        moveOneFromContainerToHopper(hopperLocation.getBlock(), storageBlock.ownerId());
    }

    private void moveOneFromHopperToContainer(Block hopperBlock, UUID ownerId) {
        if (!(hopperBlock.getState() instanceof Hopper hopper)) return;
        Inventory inventory = hopper.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;

            ItemStack moving = item.clone();
            moving.setAmount(1);
            StorageBlockHopperTransferEvent event = new StorageBlockHopperTransferEvent(ownerId, hopperBlock, moving, StorageBlockHopperTransferEvent.Direction.INTO_CONTAINER);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return;
            int added = containerManager.addItemToContainer(ownerId, moving);
            if (added <= 0) return;
            AuditLogger.logSystem("hopper-input", ownerId.toString(), "amount=" + added + " item=" + item.getType() + " hopper=" + key(hopperBlock.getLocation()));
            item.setAmount(item.getAmount() - added);
            inventory.setItem(slot, item.getAmount() > 0 ? item : null);
            return;
        }
    }

    private void moveOneFromContainerToHopper(Block hopperBlock, UUID ownerId) {
        if (!(hopperBlock.getState() instanceof Hopper hopper)) return;
        Inventory inventory = hopper.getInventory();
        ItemStack stored = containerManager.peekFirstItem(ownerId);
        if (stored == null || stored.getType().isAir()) return;

        ItemStack moving = stored.clone();
        moving.setAmount(1);
        StorageBlockHopperTransferEvent event = new StorageBlockHopperTransferEvent(ownerId, hopperBlock, moving, StorageBlockHopperTransferEvent.Direction.OUT_OF_CONTAINER);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        if (!inventory.addItem(moving).isEmpty()) return;

        ItemStack target = moving.clone();
        target.setAmount(1);
        containerManager.takeItemFromContainer(ownerId, target, 1);
        AuditLogger.logSystem("hopper-output", ownerId.toString(), "amount=1 item=" + moving.getType() + " hopper=" + key(hopperBlock.getLocation()));
    }

    private void spawnHologram(Location blockLocation) {
        if (!plugin.getConfig().getBoolean("storage-block.hologram.enabled", true)) return;
        if (blockLocation.getWorld() == null || !blockLocation.isChunkLoaded()) return;

        removeHologram(blockLocation);

        Location hologramLocation = blockLocation.clone().add(
                0.5,
                hologramYOffset(),
                0.5
        );

        TextDisplay display = (TextDisplay) blockLocation.getWorld().spawnEntity(hologramLocation, EntityType.TEXT_DISPLAY);
        display.addScoreboardTag(HOLOGRAM_TAG);
        display.addScoreboardTag(tagFor(blockLocation));
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(plugin.getConfig().getBoolean("storage-block.hologram.see-through", false));
        display.setShadowed(plugin.getConfig().getBoolean("storage-block.hologram.shadow", true));
        applyHologramBackground(display);
        display.text(hologramText(blockLocation));

        holograms.put(key(blockLocation), display.getUniqueId());
    }

    private void removeHologram(Location blockLocation) {
        String blockKey = key(blockLocation);
        UUID uuid = holograms.remove(blockKey);
        if (uuid != null) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) entity.remove();
        }
        removeTaggedHolograms(blockLocation.getWorld(), blockKey);
    }

    private Component hologramText(Location location) {
        StorageBlock storageBlock = get(location.getBlock());
        List<String> lines = getHologramLines(storageBlock);

        String owner = storageBlock != null && storageBlock.ownerName() != null ? storageBlock.ownerName() : "";
        String text = String.join("\n", lines).replace("{owner}", owner);
        Player ownerPlayer = storageBlock != null && storageBlock.ownerId() != null ? Bukkit.getPlayer(storageBlock.ownerId()) : null;
        text = PlaceholderHook.apply(ownerPlayer, text);
        return LEGACY.deserialize(VContainer.formatMessage(text));
    }

    private List<String> getHologramLines(StorageBlock storageBlock) {
        if (storageBlock != null && storageBlock.type() == StorageType.PERSONAL) {
            List<String> personalLines = plugin.getConfig().getStringList("storage-block.hologram.personal-lines");
            if (!personalLines.isEmpty()) return personalLines;
        }

        List<String> lines = plugin.getConfig().getStringList("storage-block.hologram.lines");
        if (!lines.isEmpty()) return lines;

        return List.of("&bVContainer", "&7Right click to open");
    }

    private String key(Location location) {
        return location.getWorld().getName()
                + "," + location.getBlockX()
                + "," + location.getBlockY()
                + "," + location.getBlockZ();
    }

    private String tagFor(Location location) {
        return tagForKey(key(location));
    }

    private String tagForKey(String key) {
        return "vcontainer_storage_" + key.replace(",", "_").replace("-", "m");
    }

    private Location locationFromKey(String key) {
        String[] parts = key.split(",");
        if (parts.length != 4) return null;

        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;

        try {
            return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void updateChunkHolograms(World world, int chunkX, int chunkZ, boolean loaded) {
        if (world == null) return;

        for (StorageBlock storageBlock : getStorageBlocks()) {
            Location location = locationFromKey(storageBlock.key());
            if (location == null || location.getWorld() == null) continue;
            if (!location.getWorld().getUID().equals(world.getUID())) continue;
            if ((location.getBlockX() >> 4) != chunkX || (location.getBlockZ() >> 4) != chunkZ) continue;

            if (loaded) {
                spawnHologram(location);
            } else {
                removeHologram(location);
            }
        }
    }

    private double hologramYOffset() {
        if (plugin.getConfig().contains("storage-block.hologram.y-offset")) {
            return plugin.getConfig().getDouble("storage-block.hologram.y-offset", 1.35);
        }
        return plugin.getConfig().getDouble("storage-block.hologram.height", 1.35);
    }

    private void applyHologramBackground(TextDisplay display) {
        String type = plugin.getConfig().getString("storage-block.hologram.background-type", "custom");
        if (type == null) type = "custom";

        switch (type.trim().toLowerCase()) {
            case "none" -> {
                display.setDefaultBackground(false);
                display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            }
            case "default" -> display.setDefaultBackground(true);
            case "custom" -> {
                display.setDefaultBackground(false);
                display.setBackgroundColor(parseBackgroundColor(
                        plugin.getConfig().getString("storage-block.hologram.background-color", "#50000000")
                ));
            }
            default -> display.setDefaultBackground(true);
        }
    }

    private Color parseBackgroundColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return Color.fromARGB(0x50, 0x00, 0x00, 0x00);
        }

        String value = raw.trim();
        if (value.startsWith("#")) value = value.substring(1);
        try {
            if (value.length() == 6) {
                int rgb = Integer.parseInt(value, 16);
                return Color.fromARGB(0x80, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            }
            if (value.length() == 8) {
                long argb = Long.parseLong(value, 16);
                return Color.fromARGB(
                        (int) ((argb >> 24) & 0xFF),
                        (int) ((argb >> 16) & 0xFF),
                        (int) ((argb >> 8) & 0xFF),
                        (int) (argb & 0xFF)
                );
            }
        } catch (NumberFormatException ignored) {
        }
        return Color.fromARGB(0x50, 0x00, 0x00, 0x00);
    }

    private void purgeOrphanHolograms() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
                if (entity.getScoreboardTags().contains(HOLOGRAM_TAG)) {
                    entity.remove();
                }
            }
        }
    }

    private void removeTaggedHolograms(World world, String blockKey) {
        if (world == null) return;

        String tag = tagForKey(blockKey);
        for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
            Set<String> tags = entity.getScoreboardTags();
            if (tags.contains(HOLOGRAM_TAG) && tags.contains(tag)) {
                entity.remove();
            }
        }
    }

    public enum StorageType {
        GLOBAL,
        PERSONAL
    }

    public record StorageBlock(UUID id, String key, StorageType type, UUID ownerId, String ownerName, Set<UUID> members) {
        private static StorageBlock global(UUID id, String key) {
            return new StorageBlock(id, key, StorageType.GLOBAL, null, null, new HashSet<>());
        }

        private static StorageBlock personal(UUID id, String key, UUID ownerId, String ownerName) {
            return new StorageBlock(id, key, StorageType.PERSONAL, ownerId, ownerName, new HashSet<>());
        }
    }

    private static class StorageBlockData {
        private String id;
        private String key;
        private String type;
        private String ownerId;
        private String ownerName;
        private List<String> members = new ArrayList<>();
    }

    private record DirtyBlockSave(StorageBlockData data, long version) {
    }

    private record DirtyBlockDelete(StorageType type, long version) {
    }

    private record HopperLinks(List<String> inputHopperKeys, String outputHopperKey) {
        private boolean hasAny() {
            return !inputHopperKeys.isEmpty() || outputHopperKey != null;
        }

        private boolean isStillValid(Location storageLocation) {
            if (storageLocation == null || storageLocation.getWorld() == null || !storageLocation.isChunkLoaded()) return false;
            Block storageBlock = storageLocation.getBlock();
            for (String inputKey : inputHopperKeys) {
                Location location = VContainer.getInstance().getStorageBlockManager().locationFromKey(inputKey);
                if (location == null || location.getWorld() == null || location.getBlock().getType() != Material.HOPPER) return false;
                if (!(location.getBlock().getBlockData() instanceof Directional directional)) return false;
                if (!location.getBlock().getRelative(directional.getFacing()).equals(storageBlock)) return false;
            }
            if (outputHopperKey != null) {
                Location output = VContainer.getInstance().getStorageBlockManager().locationFromKey(outputHopperKey);
                return output != null && output.getWorld() != null && output.getBlock().getType() == Material.HOPPER;
            }
            return true;
        }
    }
}
