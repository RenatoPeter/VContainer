package hu.vzone.vcontainer.utils;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.managers.StorageBlockManager;
import hu.vzone.vcontainer.storage.ContainerStorage;
import hu.vzone.vcontainer.storage.SqlContainerStorage;
import hu.vzone.vcontainer.storage.StorageSettings;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class AdminDataService {
    private static final DateTimeFormatter BACKUP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private AdminDataService() {
    }

    public static File exportBackup(VContainer plugin, String name) throws IOException {
        File backupFolder = new File(plugin.getDataFolder(), "backups");
        if (!backupFolder.exists() && !backupFolder.mkdirs()) {
            throw new IOException("Could not create backup folder.");
        }

        String safeName = sanitizeFileName(name == null || name.isBlank() ? "backup-" + LocalDateTime.now().format(BACKUP_FORMAT) : name);
        File target = new File(backupFolder, safeName.endsWith(".zip") ? safeName : safeName + ".zip");
        Path root = plugin.getDataFolder().toPath();

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target.toPath()))) {
            if (!Files.exists(root)) return target;
            try (var stream = Files.walk(root)) {
                for (Path path : stream.filter(Files::isRegularFile).toList()) {
                    if (path.startsWith(backupFolder.toPath())) continue;
                    String entryName = root.relativize(path).toString().replace('\\', '/');
                    zip.putNextEntry(new ZipEntry(entryName));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
        }
        return target;
    }

    public static void importBackup(VContainer plugin, File zipFile) throws IOException {
        validateBackup(plugin, zipFile);
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path tempRoot = Files.createTempDirectory(plugin.getDataFolder().getParentFile().toPath(), "vcontainer-import-");
        boolean extracted = false;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile.toPath()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path rootTarget = root.resolve(entry.getName()).normalize();
                if (!rootTarget.startsWith(root)) {
                    throw new IOException("Backup contains an unsafe path: " + entry.getName());
                }
                Path target = tempRoot.resolve(entry.getName()).normalize();
                if (!target.startsWith(tempRoot)) {
                    throw new IOException("Backup contains an unsafe path: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
            }
            extracted = true;
        } finally {
            if (!extracted) deleteDirectory(tempRoot);
        }

        Path rollbackRoot = Files.createTempDirectory(plugin.getDataFolder().getParentFile().toPath(), "vcontainer-import-rollback-");
        Map<Path, Path> backups = new HashMap<>();
        List<Path> createdFiles = new java.util.ArrayList<>();
        try (var stream = Files.walk(tempRoot)) {
            for (Path source : stream.filter(Files::isRegularFile).toList()) {
                Path relative = tempRoot.relativize(source);
                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Backup contains an unsafe path after extraction: " + source);
                }
                Files.createDirectories(target.getParent());
                if (Files.exists(target)) {
                    Path backup = rollbackRoot.resolve(relative).normalize();
                    Files.createDirectories(backup.getParent());
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                    backups.put(target, backup);
                } else {
                    createdFiles.add(target);
                }

                Path tempTarget = target.resolveSibling(target.getFileName() + ".import-tmp");
                Files.copy(source, tempTarget, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicMoveFailure) {
                    Files.move(tempTarget, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            restoreImportRollback(backups, createdFiles);
            throw e;
        } finally {
            deleteDirectory(tempRoot);
            deleteDirectory(rollbackRoot);
        }
    }

    public static void validateBackup(VContainer plugin, File zipFile) throws IOException {
        Path root = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        if (!zipFile.exists() || !zipFile.isFile()) throw new IOException("Backup file does not exist: " + zipFile.getName());
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile.toPath()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) {
                    throw new IOException("Backup contains an unsafe path: " + entry.getName());
                }
            }
        }
    }

    public static void migrate(VContainer plugin, ContainerManager containers, StorageBlockManager blocks, StorageSettings.StorageType targetType) throws Exception {
        containers.flushSync();
        blocks.flushSync();

        StorageSettings current = StorageSettings.from(plugin);
        StorageSettings target = migrationTarget(plugin, targetType);
        validateMigrationTarget(plugin, current, target);

        MigrationDryRun dryRun = dryRunMigration(plugin, containers, blocks, targetType);
        if (!dryRun.canRun()) throw new IllegalStateException(dryRun.message());

        Map<UUID, List<ItemStack>> containerSnapshot = containers.snapshotContainers();
        if (targetType == StorageSettings.StorageType.LOCAL) {
            saveLocalContainers(plugin, containerSnapshot);
            saveLocalBlocks(plugin, blocks);
            return;
        }

        try {
            saveSqlContainers(plugin, target, containerSnapshot);
            saveSqlBlocks(plugin, target, blocks);
        } catch (Exception e) {
            cleanupSqlTarget(plugin, target, containerSnapshot.keySet(), blocks.getStorageBlocks());
            throw e;
        }
    }

    private static void saveLocalContainers(VContainer plugin, Map<UUID, List<ItemStack>> containers) throws IOException {
        File folder = new File(plugin.getStorageFolder(), "player_data");
        if (!folder.exists() && !folder.mkdirs()) throw new IOException("Could not create local player_data folder.");

        for (Map.Entry<UUID, List<ItemStack>> entry : containers.entrySet()) {
            Map<String, String> wrapper = new HashMap<>();
            wrapper.put("items_base64", ItemUtils.itemsToBase64(entry.getValue()));
            Files.writeString(new File(folder, entry.getKey() + ".json").toPath(), plugin.getGson().toJson(wrapper), StandardCharsets.UTF_8);
        }
    }

    private static void saveLocalBlocks(VContainer plugin, StorageBlockManager blocks) throws IOException {
        File global = new File(plugin.getStorageFolder(), "global_storage_blocks");
        File personal = new File(plugin.getStorageFolder(), "personal_storage_blocks");
        if (!global.exists() && !global.mkdirs()) throw new IOException("Could not create global_storage_blocks folder.");
        if (!personal.exists() && !personal.mkdirs()) throw new IOException("Could not create personal_storage_blocks folder.");

        for (StorageBlockManager.StorageBlock block : blocks.getStorageBlocks()) {
            File folder = block.type() == StorageBlockManager.StorageType.PERSONAL ? personal : global;
            Files.writeString(new File(folder, block.id() + ".json").toPath(), plugin.getGson().toJson(blockData(block)), StandardCharsets.UTF_8);
        }
    }

    private static void saveSqlContainers(VContainer plugin, StorageSettings target, Map<UUID, List<ItemStack>> containers) {
        try (ContainerStorage storage = new SqlContainerStorage(plugin, target)) {
            for (Map.Entry<UUID, List<ItemStack>> entry : containers.entrySet()) {
                if (!storage.save(entry.getKey(), entry.getValue())) {
                    throw new IllegalStateException("Failed to migrate container " + entry.getKey());
                }
            }
        }
    }

    private static void saveSqlBlocks(VContainer plugin, StorageSettings target, StorageBlockManager blocks) throws Exception {
        String globalTable = target.prefix() + "global_storage_blocks";
        String personalTable = target.prefix() + "personal_storage_blocks";

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(target.buildJdbcUrl(plugin.getDataFolder()));
        config.setMaximumPoolSize(target.poolSize());
        String driverClass = target.resolvedDriverClass();
        if (!driverClass.isBlank()) config.setDriverClassName(driverClass);
        if (target.type() != StorageSettings.StorageType.H2) {
            config.setUsername(target.username());
            config.setPassword(target.password());
        }

        try (HikariDataSource dataSource = new HikariDataSource(config)) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + globalTable + " (id VARCHAR(36) PRIMARY KEY, location_key VARCHAR(255) NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + personalTable + " (id VARCHAR(36) PRIMARY KEY, location_key VARCHAR(255) NOT NULL, owner_uuid VARCHAR(36) NOT NULL, owner_name VARCHAR(64) NOT NULL, members TEXT)");
            }

            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
            try (PreparedStatement globalInsert = connection.prepareStatement("INSERT INTO " + globalTable + " (id, location_key) VALUES (?, ?)");
                 PreparedStatement personalInsert = connection.prepareStatement("INSERT INTO " + personalTable + " (id, location_key, owner_uuid, owner_name, members) VALUES (?, ?, ?, ?, ?)")) {
                for (StorageBlockManager.StorageBlock block : blocks.getStorageBlocks()) {
                    if (block.type() == StorageBlockManager.StorageType.PERSONAL) {
                        personalInsert.setString(1, block.id().toString());
                        personalInsert.setString(2, block.key());
                        personalInsert.setString(3, block.ownerId().toString());
                        personalInsert.setString(4, block.ownerName());
                        personalInsert.setString(5, plugin.getGson().toJson(block.members().stream().map(UUID::toString).sorted().toList()));
                        personalInsert.addBatch();
                    } else {
                        globalInsert.setString(1, block.id().toString());
                        globalInsert.setString(2, block.key());
                        globalInsert.addBatch();
                    }
                }
                globalInsert.executeBatch();
                personalInsert.executeBatch();
            }

            connection.commit();
            }
        } catch (Exception e) {
            throw e;
        }
    }

    private static void cleanupSqlTarget(VContainer plugin, StorageSettings target, Iterable<UUID> ownerIds, Iterable<StorageBlockManager.StorageBlock> storageBlocks) {
        if (target.type() == StorageSettings.StorageType.LOCAL) return;
        try (HikariDataSource dataSource = dataSource(plugin, target);
             Connection connection = dataSource.getConnection()) {
            deleteOwnersIfExists(connection, target.prefix() + "player_data", ownerIds);
            deleteBlocksIfExists(connection, target.prefix() + "global_storage_blocks", storageBlocks, StorageBlockManager.StorageType.GLOBAL);
            deleteBlocksIfExists(connection, target.prefix() + "personal_storage_blocks", storageBlocks, StorageBlockManager.StorageType.PERSONAL);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to clean partially migrated SQL target: " + e.getMessage());
        }
    }

    private static void deleteOwnersIfExists(Connection connection, String table, Iterable<UUID> ownerIds) throws Exception {
        if (!tableExists(connection, table)) return;
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE owner_uuid = ?")) {
            for (UUID ownerId : ownerIds) {
                statement.setString(1, ownerId.toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void deleteBlocksIfExists(Connection connection, String table, Iterable<StorageBlockManager.StorageBlock> storageBlocks, StorageBlockManager.StorageType type) throws Exception {
        if (!tableExists(connection, table)) return;
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE id = ?")) {
            for (StorageBlockManager.StorageBlock block : storageBlocks) {
                if (block.type() != type) continue;
                statement.setString(1, block.id().toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void deleteDirectory(Path folder) {
        if (folder == null || !Files.exists(folder)) return;
        try (var stream = Files.walk(folder)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    private static void restoreImportRollback(Map<Path, Path> backups, List<Path> createdFiles) {
        for (Path created : createdFiles) {
            try {
                Files.deleteIfExists(created);
            } catch (IOException ignored) {
            }
        }
        for (Map.Entry<Path, Path> entry : backups.entrySet()) {
            try {
                Files.createDirectories(entry.getKey().getParent());
                Files.copy(entry.getValue(), entry.getKey(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
        }
    }

    public static MigrationDryRun dryRunMigration(VContainer plugin, ContainerManager containers, StorageBlockManager blocks, StorageSettings.StorageType targetType) throws Exception {
        StorageSettings current = StorageSettings.from(plugin);
        StorageSettings target = migrationTarget(plugin, targetType);
        validateMigrationTarget(plugin, current, target);

        int playerContainers = containers.snapshotContainers().size();
        int storageBlocks = blocks.getStorageBlocks().size();
        if (targetType == StorageSettings.StorageType.LOCAL) {
            File storageFolder = plugin.getStorageFolder();
            boolean targetExists = false;
            if (storageFolder.exists()) {
                try (var stream = Files.walk(storageFolder.toPath())) {
                    targetExists = stream.anyMatch(Files::isRegularFile);
                }
            }
            if (targetExists) {
                return new MigrationDryRun(false, "LOCAL target storage folder is not empty.", playerContainers, storageBlocks, 0, 0, target.buildJdbcUrl(plugin.getDataFolder()), target.prefix());
            }
            return new MigrationDryRun(true, "LOCAL target is ready.", playerContainers, storageBlocks, 0, 0, target.buildJdbcUrl(plugin.getDataFolder()), target.prefix());
        }

        try (HikariDataSource dataSource = dataSource(plugin, target);
             Connection connection = dataSource.getConnection()) {
            String playerTable = target.prefix() + "player_data";
            String globalTable = target.prefix() + "global_storage_blocks";
            String personalTable = target.prefix() + "personal_storage_blocks";
            int existingPlayers = tableExists(connection, playerTable) ? countRows(connection, playerTable) : 0;
            int existingBlocks = (tableExists(connection, globalTable) ? countRows(connection, globalTable) : 0)
                    + (tableExists(connection, personalTable) ? countRows(connection, personalTable) : 0);
            boolean empty = existingPlayers == 0 && existingBlocks == 0;
            return new MigrationDryRun(empty, empty ? "SQL target is ready." : "SQL target tables are not empty.", playerContainers, storageBlocks, existingPlayers, existingBlocks, target.buildJdbcUrl(plugin.getDataFolder()), target.prefix());
        }
    }

    public static RepairReport repair(VContainer plugin, String mode, boolean exportBad) throws Exception {
        StorageSettings settings = StorageSettings.from(plugin);
        if (settings.type() == StorageSettings.StorageType.LOCAL || mode.equalsIgnoreCase("local")) {
            return repairLocal(plugin, exportBad);
        }
        return repairSql(plugin, settings, exportBad);
    }

    private static RepairReport repairLocal(VContainer plugin, boolean exportBad) throws IOException {
        File folder = new File(plugin.getStorageFolder(), "player_data");
        File badFolder = new File(plugin.getDataFolder(), "repair/bad-local-" + LocalDateTime.now().format(BACKUP_FORMAT));
        int checked = 0;
        int bad = 0;
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                checked++;
                if (!isValidPlayerData(plugin, file)) {
                    bad++;
                    exportBadFile(file, badFolder, exportBad);
                }
            }
        }
        RepairCounter globalBlocks = repairLocalBlockFolder(plugin, new File(plugin.getStorageFolder(), "global_storage_blocks"), false, badFolder, exportBad);
        RepairCounter personalBlocks = repairLocalBlockFolder(plugin, new File(plugin.getStorageFolder(), "personal_storage_blocks"), true, badFolder, exportBad);
        checked += globalBlocks.checked() + personalBlocks.checked();
        bad += globalBlocks.bad() + personalBlocks.bad();
        return new RepairReport(checked, bad, exportBad && bad > 0 ? "Bad files exported to " + badFolder.getName() : "Local repair scan complete.");
    }

    private static RepairReport repairSql(VContainer plugin, StorageSettings settings, boolean exportBad) throws Exception {
        String table = settings.prefix() + "player_data";
        int checked = 0;
        int bad = 0;
        File export = new File(plugin.getDataFolder(), "repair/bad-sql-" + LocalDateTime.now().format(BACKUP_FORMAT) + ".txt");
        StringBuilder exported = new StringBuilder();
        try (HikariDataSource dataSource = dataSource(plugin, settings);
             Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT owner_uuid, slot_index, item_blob FROM " + table);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                checked++;
                try {
                    ItemUtils.fromBytes(result.getBytes("item_blob"));
                } catch (Exception e) {
                    bad++;
                    if (exportBad) exported.append("player_data:")
                            .append(result.getString("owner_uuid")).append(':').append(result.getInt("slot_index")).append(System.lineSeparator());
                }
            }
            RepairCounter global = repairSqlBlockTable(connection, settings.prefix() + "global_storage_blocks", false, exported, exportBad);
            RepairCounter personal = repairSqlBlockTable(connection, settings.prefix() + "personal_storage_blocks", true, exported, exportBad);
            checked += global.checked() + personal.checked();
            bad += global.bad() + personal.bad();
            if (exportBad && exported.length() > 0) {
                export.getParentFile().mkdirs();
                Files.writeString(export.toPath(), exported.toString(), StandardCharsets.UTF_8);
            }
        }
        return new RepairReport(checked, bad, exportBad && bad > 0 ? "Bad SQL entries exported to " + export.getName() : "SQL repair scan complete.");
    }

    private static boolean isValidPlayerData(VContainer plugin, File file) {
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Map<?, ?> wrapper = plugin.getGson().fromJson(json, Map.class);
            Object base64 = wrapper == null ? null : wrapper.get("items_base64");
            if (!(base64 instanceof String text)) throw new IOException("Missing items_base64");
            ItemUtils.itemsFromBase64(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static RepairCounter repairLocalBlockFolder(VContainer plugin, File folder, boolean personal, File badFolder, boolean exportBad) throws IOException {
        int checked = 0;
        int bad = 0;
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return new RepairCounter(0, 0);
        for (File file : files) {
            checked++;
            try {
                Map<?, ?> data = plugin.getGson().fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), Map.class);
                validateBlockData(data, personal);
            } catch (Exception e) {
                bad++;
                exportBadFile(file, badFolder, exportBad);
            }
        }
        return new RepairCounter(checked, bad);
    }

    private static RepairCounter repairSqlBlockTable(Connection connection, String table, boolean personal, StringBuilder exported, boolean exportBad) throws Exception {
        if (!tableExists(connection, table)) return new RepairCounter(0, 0);
        int checked = 0;
        int bad = 0;
        String columns = personal ? "id, location_key, owner_uuid, owner_name, members" : "id, location_key";
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + columns + " FROM " + table);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                checked++;
                try {
                    UUID.fromString(result.getString("id"));
                    if (result.getString("location_key") == null || result.getString("location_key").isBlank()) throw new IOException("Missing location_key");
                    if (personal) {
                        UUID.fromString(result.getString("owner_uuid"));
                        if (result.getString("owner_name") == null || result.getString("owner_name").isBlank()) throw new IOException("Missing owner_name");
                        String members = result.getString("members");
                        if (members != null && !members.isBlank()) {
                            VContainer.getInstance().getGson().fromJson(members, String[].class);
                        }
                    }
                } catch (Exception e) {
                    bad++;
                    if (exportBad) exported.append(table).append(':').append(result.getString("id")).append(System.lineSeparator());
                }
            }
        }
        return new RepairCounter(checked, bad);
    }

    private static void validateBlockData(Map<?, ?> data, boolean personal) throws IOException {
        if (data == null) throw new IOException("Invalid JSON");
        Object id = data.get("id");
        Object key = data.get("key");
        if (!(id instanceof String idText)) throw new IOException("Missing id");
        if (!(key instanceof String keyText) || keyText.isBlank()) throw new IOException("Missing key");
        UUID.fromString(idText);
        if (personal) {
            Object ownerId = data.get("ownerId");
            Object ownerName = data.get("ownerName");
            if (!(ownerId instanceof String ownerText)) throw new IOException("Missing ownerId");
            if (!(ownerName instanceof String nameText) || nameText.isBlank()) throw new IOException("Missing ownerName");
            UUID.fromString(ownerText);
            Object members = data.get("members");
            if (members != null && !(members instanceof List<?>)) throw new IOException("Invalid members");
        }
    }

    private static void exportBadFile(File file, File badFolder, boolean exportBad) throws IOException {
        if (!exportBad) return;
        badFolder.mkdirs();
        Files.copy(file.toPath(), new File(badFolder, file.getParentFile().getName() + "-" + file.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static HikariDataSource dataSource(VContainer plugin, StorageSettings target) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(target.buildJdbcUrl(plugin.getDataFolder()));
        config.setMaximumPoolSize(target.poolSize());
        String driverClass = target.resolvedDriverClass();
        if (!driverClass.isBlank()) config.setDriverClassName(driverClass);
        if (target.type() != StorageSettings.StorageType.H2) {
            config.setUsername(target.username());
            config.setPassword(target.password());
        }
        return new HikariDataSource(config);
    }

    private static boolean tableExists(Connection connection, String table) throws Exception {
        try (ResultSet result = connection.getMetaData().getTables(null, null, table, null)) {
            if (result.next()) return true;
        }
        try (ResultSet result = connection.getMetaData().getTables(null, null, table.toUpperCase(), null)) {
            return result.next();
        }
    }

    private static int countRows(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    public static StorageSettings migrationTarget(VContainer plugin, StorageSettings.StorageType targetType) {
        if (targetType == StorageSettings.StorageType.LOCAL) {
            StorageSettings current = StorageSettings.from(plugin);
            return new StorageSettings(targetType, current.hostname(), current.port(), current.username(), current.password(),
                    current.database(), current.poolSize(), current.useSsl(), "", "", current.prefix());
        }

        FileConfiguration config = plugin.getMigrationConfig();
        if (!config.getBoolean("migration.enabled", true)) {
            throw new IllegalStateException("Migration is disabled in migration.yml.");
        }

        String base = "migration.targets." + targetType.name() + ".";
        return new StorageSettings(
                targetType,
                config.getString(base + "Hostname", "127.0.0.1"),
                config.getInt(base + "Port", targetType == StorageSettings.StorageType.H2 ? 0 : 3306),
                config.getString(base + "Username", "minecraft"),
                config.getString(base + "Password", ""),
                config.getString(base + "Database", targetType == StorageSettings.StorageType.H2 ? "vcontainer-migrated" : "minecraft_migrated"),
                Math.max(1, config.getInt(base + "Pool Size", 5)),
                config.getBoolean(base + "Use SSL", false),
                config.getString(base + "Jdbc Url", ""),
                config.getString(base + "Driver Class", ""),
                sanitizePrefix(config.getString(base + "Prefix", "vcontainer_"))
        );
    }

    private static void validateMigrationTarget(VContainer plugin, StorageSettings current, StorageSettings target) {
        if (current.type() == target.type()
                && current.prefix().equalsIgnoreCase(target.prefix())
                && current.buildJdbcUrl(plugin.getDataFolder()).equalsIgnoreCase(target.buildJdbcUrl(plugin.getDataFolder()))) {
            throw new IllegalStateException("Migration target matches the active storage backend. Change migration.yml first.");
        }

        if (target.type() != StorageSettings.StorageType.LOCAL && !target.jdbcUrl().isBlank()
                && current.buildJdbcUrl(plugin.getDataFolder()).equalsIgnoreCase(target.jdbcUrl())) {
            throw new IllegalStateException("Migration Jdbc Url points to the active backend.");
        }
    }

    private static String sanitizePrefix(String prefix) {
        String safe = prefix == null ? "vcontainer_" : prefix.replaceAll("[^A-Za-z0-9_]", "");
        return safe.isBlank() ? "vcontainer_" : safe;
    }

    private static Object blockData(StorageBlockManager.StorageBlock block) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", block.id().toString());
        data.put("key", block.key());
        data.put("type", block.type().name());
        data.put("ownerId", block.ownerId() == null ? null : block.ownerId().toString());
        data.put("ownerName", block.ownerName());
        data.put("members", block.members().stream().map(UUID::toString).sorted().toList());
        return data;
    }

    private static String sanitizeFileName(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.isBlank() ? "backup-" + LocalDateTime.now().format(BACKUP_FORMAT) : safe;
    }

    public record MigrationDryRun(
            boolean canRun,
            String message,
            int playerContainers,
            int storageBlocks,
            int existingPlayerRows,
            int existingStorageBlockRows,
            String jdbcUrl,
            String prefix
    ) {
    }

    public record RepairReport(int checked, int bad, String message) {
    }

    private record RepairCounter(int checked, int bad) {
    }
}
