package hu.vzone.vcontainer.managers;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.gui.ContainerGUI;
import hu.vzone.vcontainer.storage.StorageSettings;
import hu.vzone.vcontainer.utils.ItemUtils;
import hu.vzone.vcontainer.utils.PermissionUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
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
    private BukkitTask hopperTask;

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
        startHopperTask();
    }

    public boolean add(Block block) {
        String key = key(block.getLocation());
        if (globalBlocks.containsKey(key) || personalBlocks.containsKey(key)) return false;

        StorageBlock storageBlock = StorageBlock.global(UUID.randomUUID(), key);
        globalBlocks.put(key, storageBlock);
        spawnHologram(block.getLocation());
        saveBlock(storageBlock);
        return true;
    }

    public boolean addPersonal(Block block, Player owner) {
        String key = key(block.getLocation());
        if (globalBlocks.containsKey(key) || personalBlocks.containsKey(key)) return false;

        StorageBlock storageBlock = StorageBlock.personal(UUID.randomUUID(), key, owner.getUniqueId(), owner.getName());
        personalBlocks.put(key, storageBlock);
        spawnHologram(block.getLocation());
        saveBlock(storageBlock);
        return true;
    }

    public boolean removeGlobal(Block block) {
        if (block == null) return false;
        String key = key(block.getLocation());
        StorageBlock removed = globalBlocks.remove(key);
        if (removed == null) return false;

        removeHologram(block.getLocation());
        deleteBlockFile(removed);
        return true;
    }

    public boolean removePersonal(String key, boolean keepBlock) {
        StorageBlock removed = personalBlocks.remove(key);
        if (removed == null) return false;

        Location location = locationFromKey(key);
        if (location != null) {
            removeHologram(location);
            if (!keepBlock && location.getWorld() != null) {
                location.getBlock().setType(Material.AIR);
            }
        }

        deleteBlockFile(removed);
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

    public boolean canAccess(Player player, StorageBlock storageBlock) {
        if (storageBlock == null) return false;
        if (storageBlock.type() == StorageType.GLOBAL) return true;
        if (player.isOp()) return true;
        return storageBlock.ownerId().equals(player.getUniqueId()) || storageBlock.members().contains(player.getUniqueId());
    }

    public boolean isOwner(Player player, StorageBlock storageBlock) {
        return storageBlock != null && storageBlock.type() == StorageType.PERSONAL && storageBlock.ownerId().equals(player.getUniqueId());
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
        saveBlock(storageBlock);
    }

    public boolean setMember(String key, UUID memberId, boolean member) {
        StorageBlock storageBlock = personalBlocks.get(key);
        if (storageBlock == null || memberId == null || storageBlock.ownerId().equals(memberId)) return false;

        boolean changed = member ? storageBlock.members().add(memberId) : storageBlock.members().remove(memberId);
        if (changed) saveBlock(storageBlock);
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

    private int countPersonalBlocksInChunk(Block block, UUID ownerId) {
        if (block == null || block.getWorld() == null) return 0;

        String worldName = block.getWorld().getName();
        int chunkX = block.getX() >> 4;
        int chunkZ = block.getZ() >> 4;
        int count = 0;

        for (StorageBlock storageBlock : personalBlocks.values()) {
            if (!ownerId.equals(storageBlock.ownerId())) continue;

            Location location = locationFromKey(storageBlock.key());
            if (location == null || location.getWorld() == null) continue;
            if (!location.getWorld().getName().equals(worldName)) continue;
            if ((location.getBlockX() >> 4) == chunkX && (location.getBlockZ() >> 4) == chunkZ) {
                count++;
            }
        }
        return count;
    }

    public void reloadHolograms() {
        removeAllHolograms();
        for (String key : globalBlocks.keySet()) {
            Location location = locationFromKey(key);
            if (location != null) spawnHologram(location);
        }
        for (String key : personalBlocks.keySet()) {
            Location location = locationFromKey(key);
            if (location != null) spawnHologram(location);
        }
    }

    public void reload() {
        if (hopperTask != null) hopperTask.cancel();
        startHopperTask();
        reloadHolograms();
    }

    public void removeAllHolograms() {
        for (String key : globalBlocks.keySet()) {
            Location location = locationFromKey(key);
            if (location != null) removeHologram(location);
        }
        for (String key : personalBlocks.keySet()) {
            Location location = locationFromKey(key);
            if (location != null) removeHologram(location);
        }
        holograms.clear();
    }

    public void shutdown() {
        if (hopperTask != null) hopperTask.cancel();
        removeAllHolograms();
        if (sqlDataSource != null) sqlDataSource.close();
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
                personalBlocks.put(key, new StorageBlock(UUID.randomUUID(), key, type, ownerId, ownerName, members));
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
            personalBlocks.put(key, new StorageBlock(UUID.randomUUID(), key, StorageType.PERSONAL, ownerId, ownerName, members));
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
                personalBlocks.put(key, new StorageBlock(id, key, StorageType.PERSONAL, ownerId, ownerName, members));
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
        for (StorageBlock storageBlock : globalBlocks.values()) saveBlock(storageBlock);
        for (StorageBlock storageBlock : personalBlocks.values()) saveBlock(storageBlock);
    }

    private void saveBlock(StorageBlock storageBlock) {
        if (!localStorage) {
            saveSqlBlock(storageBlock);
            return;
        }
        ensureStorageDirectories();
        File file = fileFor(storageBlock);
        try {
            Files.writeString(file.toPath(), plugin.getGson().toJson(toData(storageBlock)), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save storage block " + storageBlock.id() + ": " + e.getMessage());
        }
    }

    private void deleteBlockFile(StorageBlock storageBlock) {
        if (!localStorage) {
            deleteSqlBlock(storageBlock);
            return;
        }
        try {
            Files.deleteIfExists(fileFor(storageBlock).toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to delete storage block file " + storageBlock.id() + ": " + e.getMessage());
        }
    }

    private void saveSqlBlock(StorageBlock storageBlock) {
        String table = storageBlock.type() == StorageType.PERSONAL ? personalTable : globalTable;
        String deleteSql = "DELETE FROM " + table + " WHERE id = ?";
        String insertSql = storageBlock.type() == StorageType.PERSONAL
                ? "INSERT INTO " + table + " (id, location_key, owner_uuid, owner_name, members) VALUES (?, ?, ?, ?, ?)"
                : "INSERT INTO " + table + " (id, location_key) VALUES (?, ?)";

        try (Connection connection = sqlDataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement delete = connection.prepareStatement(deleteSql)) {
                delete.setString(1, storageBlock.id().toString());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                insert.setString(1, storageBlock.id().toString());
                insert.setString(2, storageBlock.key());
                if (storageBlock.type() == StorageType.PERSONAL) {
                    insert.setString(3, storageBlock.ownerId().toString());
                    insert.setString(4, storageBlock.ownerName());
                    insert.setString(5, plugin.getGson().toJson(storageBlock.members().stream().map(UUID::toString).sorted().toList()));
                }
                insert.executeUpdate();
            }
            connection.commit();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save SQL storage block " + storageBlock.id() + ": " + e.getMessage());
        }
    }

    private void deleteSqlBlock(StorageBlock storageBlock) {
        String table = storageBlock.type() == StorageType.PERSONAL ? personalTable : globalTable;
        String sql = "DELETE FROM " + table + " WHERE id = ?";
        try (Connection connection = sqlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, storageBlock.id().toString());
            statement.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to delete SQL storage block " + storageBlock.id() + ": " + e.getMessage());
        }
    }

    private File fileFor(StorageBlock storageBlock) {
        File directory = storageBlock.type() == StorageType.PERSONAL ? personalDirectory : globalDirectory;
        return new File(directory, storageBlock.id() + ".json");
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

    private void tickHoppers() {
        if (!plugin.getConfig().getBoolean("storage-block.hoppers.enabled", true)) return;
        for (StorageBlock storageBlock : personalBlocks.values()) {
            Location location = locationFromKey(storageBlock.key());
            if (location == null || location.getWorld() == null || !location.isChunkLoaded()) continue;

            if (plugin.getConfig().getBoolean("storage-block.hoppers.input", true)) {
                tickInputHoppers(location, storageBlock);
            }
            if (plugin.getConfig().getBoolean("storage-block.hoppers.output", true)) {
                tickOutputHopper(location, storageBlock);
            }
        }
    }

    private void tickInputHoppers(Location location, StorageBlock storageBlock) {
        for (BlockFace face : HOPPER_INPUT_FACES) {
            Block hopperBlock = location.getBlock().getRelative(face);
            if (hopperBlock.getType() != Material.HOPPER || !(hopperBlock.getBlockData() instanceof Directional directional)) continue;
            if (hopperBlock.getRelative(directional.getFacing()).equals(location.getBlock())) {
                moveOneFromHopperToContainer(hopperBlock, storageBlock.ownerId());
            }
        }
    }

    private void tickOutputHopper(Location location, StorageBlock storageBlock) {
        Block hopperBlock = location.getBlock().getRelative(BlockFace.DOWN);
        if (hopperBlock.getType() != Material.HOPPER) return;
        moveOneFromContainerToHopper(hopperBlock, storageBlock.ownerId());
    }

    private void moveOneFromHopperToContainer(Block hopperBlock, UUID ownerId) {
        if (!(hopperBlock.getState() instanceof Hopper hopper)) return;
        Inventory inventory = hopper.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;

            ItemStack moving = item.clone();
            moving.setAmount(1);
            containerManager.addItemToContainer(ownerId, moving);
            ContainerGUI.queueRefresh(ownerId);
            item.setAmount(item.getAmount() - 1);
            inventory.setItem(slot, item.getAmount() > 0 ? item : null);
            return;
        }
    }

    private void moveOneFromContainerToHopper(Block hopperBlock, UUID ownerId) {
        if (!(hopperBlock.getState() instanceof Hopper hopper)) return;
        Inventory inventory = hopper.getInventory();
        for (ItemStack stored : containerManager.getAllItemFromContainer(ownerId)) {
            ItemStack moving = stored.clone();
            moving.setAmount(1);
            if (!inventory.addItem(moving).isEmpty()) return;

            ItemStack target = moving.clone();
            target.setAmount(1);
            containerManager.takeItemFromContainer(ownerId, target, 1);
            ContainerGUI.queueRefresh(ownerId);
            return;
        }
    }

    private void spawnHologram(Location blockLocation) {
        if (!plugin.getConfig().getBoolean("storage-block.hologram.enabled", true)) return;
        if (blockLocation.getWorld() == null) return;

        removeHologram(blockLocation);

        Location hologramLocation = blockLocation.clone().add(
                0.5,
                plugin.getConfig().getDouble("storage-block.hologram.height", 1.35),
                0.5
        );

        TextDisplay display = (TextDisplay) blockLocation.getWorld().spawnEntity(hologramLocation, EntityType.TEXT_DISPLAY);
        display.addScoreboardTag(HOLOGRAM_TAG);
        display.addScoreboardTag(tagFor(blockLocation));
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(plugin.getConfig().getBoolean("storage-block.hologram.see-through", false));
        display.setShadowed(plugin.getConfig().getBoolean("storage-block.hologram.shadow", true));
        display.text(hologramText(blockLocation));

        holograms.put(key(blockLocation), display.getUniqueId());
    }

    private void removeHologram(Location blockLocation) {
        UUID uuid = holograms.remove(key(blockLocation));
        if (uuid != null) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) entity.remove();
        }

        if (blockLocation.getWorld() == null) return;
        String tag = tagFor(blockLocation);
        for (Entity entity : blockLocation.getWorld().getNearbyEntities(blockLocation.clone().add(0.5, 1.0, 0.5), 1.5, 2.5, 1.5)) {
            if (entity.getScoreboardTags().contains(HOLOGRAM_TAG) && entity.getScoreboardTags().contains(tag)) {
                entity.remove();
            }
        }
    }

    private Component hologramText(Location location) {
        StorageBlock storageBlock = get(location.getBlock());
        List<String> lines = getHologramLines(storageBlock);

        String owner = storageBlock != null && storageBlock.ownerName() != null ? storageBlock.ownerName() : "";
        String text = String.join("\n", lines).replace("{owner}", owner);
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
        return "vcontainer_storage_" + key(location).replace(",", "_").replace("-", "m");
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
}
