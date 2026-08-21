package hu.vzone.vcontainer.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.utils.ItemUtils;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SqlContainerStorage implements ContainerStorage {
    private static final int SAVE_ATTEMPTS = 3;

    private final VContainer plugin;
    private final HikariDataSource dataSource;
    private final String table;
    private final StorageSettings.StorageType type;
    private volatile boolean initialized;
    private volatile boolean initializationFailed;

    public SqlContainerStorage(VContainer plugin, StorageSettings settings) {
        this.plugin = plugin;
        this.table = settings.prefix() + "player_data";
        this.type = settings.type();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.buildJdbcUrl(plugin.getDataFolder()));
        config.setMaximumPoolSize(settings.poolSize());
        config.setPoolName("VContainer-" + settings.type().name());
        config.setConnectionTimeout(5_000L);
        config.setValidationTimeout(3_000L);
        config.setKeepaliveTime(60_000L);
        // Do not open a database socket from the plugin enable/main thread. The first worker operation initializes it.
        config.setInitializationFailTimeout(-1L);

        String driverClass = settings.resolvedDriverClass();
        if (!driverClass.isBlank()) config.setDriverClassName(driverClass);
        if (settings.type() != StorageSettings.StorageType.H2) {
            config.setUsername(settings.username());
            config.setPassword(settings.password());
        }

        this.dataSource = new HikariDataSource(config);
    }

    @Override
    public PlayerContainerLoadResult load(UUID ownerId) {
        if (ownerId == null) return PlayerContainerLoadResult.failure("Missing owner UUID.");
        if (!ensureInitialized()) return PlayerContainerLoadResult.failure("SQL storage initialization failed.");
        List<ItemStack> items = new ArrayList<>();
        String sql = "SELECT item_blob, item_amount FROM " + table + " WHERE owner_uuid = ? ORDER BY slot_index";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId.toString());
            try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                try {
                    ItemStack item = ItemUtils.fromBytes(result.getBytes("item_blob"));
                    if (item == null || item.getType().isAir()) continue;
                    Number storedAmount = (Number) result.getObject("item_amount");
                    if (storedAmount != null && storedAmount.intValue() > 0) {
                        item.setAmount(storedAmount.intValue());
                    }
                    items.add(item);
                } catch (Exception rowException) {
                    plugin.getLogger().severe("Corrupt SQL container row for " + ownerId + ": " + rowException.getMessage());
                    return PlayerContainerLoadResult.failure("Corrupt SQL container data.");
                }
            }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load SQL container for " + ownerId + ": " + e.getMessage());
            return PlayerContainerLoadResult.failure(e.getMessage());
        }

        return PlayerContainerLoadResult.success(items);
    }

    @Override
    public boolean save(UUID ownerId, List<ItemStack> items) {
        if (!ensureInitialized()) return false;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try {
                Map<Integer, StoredItem> existing = loadExisting(connection, ownerId);
                List<StoredItem> desired = serializeItems(items);

                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE owner_uuid = ? AND slot_index = ?");
                     PreparedStatement upsert = connection.prepareStatement(upsertSql())) {
                    for (Map.Entry<Integer, StoredItem> entry : existing.entrySet()) {
                        if (entry.getKey() >= desired.size() || !entry.getValue().equals(desired.get(entry.getKey()))) {
                            delete.setString(1, ownerId.toString());
                            delete.setInt(2, entry.getKey());
                            delete.addBatch();
                        }
                    }

                    for (int slot = 0; slot < desired.size(); slot++) {
                        StoredItem item = desired.get(slot);
                        if (item.equals(existing.get(slot))) continue;

                        upsert.setString(1, ownerId.toString());
                        upsert.setInt(2, slot);
                        upsert.setBytes(3, item.data());
                        upsert.setInt(4, item.amount());
                        upsert.addBatch();
                    }

                    delete.executeBatch();
                    upsert.executeBatch();
                }

                connection.commit();
                return true;
            } catch (Exception e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    plugin.getLogger().severe("Failed to rollback SQL container save for " + ownerId + ": " + rollbackException.getMessage());
                }
                plugin.getLogger().severe("Failed to save SQL container for " + ownerId + ": " + e.getMessage());
                return false;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to open SQL connection for container save " + ownerId + ": " + e.getMessage());
            return false;
        }
    }

    private String upsertSql() {
        if (type == StorageSettings.StorageType.H2) {
            return "MERGE INTO " + table + " (owner_uuid, slot_index, item_blob, item_amount) KEY(owner_uuid, slot_index) VALUES (?, ?, ?, ?)";
        }
        return "INSERT INTO " + table + " (owner_uuid, slot_index, item_blob, item_amount) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE item_blob = VALUES(item_blob), item_amount = VALUES(item_amount)";
    }

    /**
     * Replaces every changed owner passed by the cache manager in one transaction. The table itself is never
     * truncated or recreated, so a failed save rolls back to the previously committed data.
     */
    @Override
    public boolean saveAll(Map<UUID, List<ItemStack>> containers) {
        if (!ensureInitialized()) return false;
        Exception failure = null;
        for (int attempt = 1; attempt <= SAVE_ATTEMPTS; attempt++) {
            try {
                saveAllAttempt(containers);
                return true;
            } catch (Exception exception) {
                failure = exception;
                if (!isConnectionFailure(exception) || attempt == SAVE_ATTEMPTS) break;
                plugin.getLogger().warning("SQL container save lost its connection; retrying "
                        + "(" + attempt + "/" + SAVE_ATTEMPTS + "): " + exception.getMessage());
            }
        }
        plugin.getLogger().severe("Failed to save changed SQL containers after " + SAVE_ATTEMPTS
                + " attempt(s): " + (failure == null ? "unknown error" : failure.getMessage()));
        return false;
    }

    /** Saves one dirty-cache snapshot atomically. A failed transaction never deletes committed container data. */
    private void saveAllAttempt(Map<UUID, List<ItemStack>> containers) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement deleteOwner = connection.prepareStatement("DELETE FROM " + table + " WHERE owner_uuid = ?");
                     PreparedStatement insert = connection.prepareStatement(upsertSql())) {
                    int pendingDeletes = 0;
                    for (UUID ownerId : containers.keySet()) {
                        deleteOwner.setString(1, ownerId.toString());
                        deleteOwner.addBatch();
                        if (++pendingDeletes >= 500) {
                            deleteOwner.executeBatch();
                            pendingDeletes = 0;
                        }
                    }
                    if (pendingDeletes > 0) deleteOwner.executeBatch();

                    int pendingInserts = 0;
                    for (Map.Entry<UUID, List<ItemStack>> entry : containers.entrySet()) {
                        int slot = 0;
                        for (ItemStack item : entry.getValue()) {
                            if (item == null || item.getType().isAir()) continue;
                            ItemStack data = item.clone();
                            data.setAmount(1);

                            insert.setString(1, entry.getKey().toString());
                            insert.setInt(2, slot++);
                            insert.setBytes(3, ItemUtils.toBytes(data));
                            insert.setInt(4, item.getAmount());
                            insert.addBatch();
                            if (++pendingInserts >= 500) {
                                insert.executeBatch();
                                pendingInserts = 0;
                            }
                        }
                    }
                    if (pendingInserts > 0) insert.executeBatch();
                }

                connection.commit();
            } catch (Exception exception) {
                rollback(connection, "full cache save");
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    private boolean isConnectionFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();
                if (state != null && state.startsWith("08")) return true;
            }
        }
        return false;
    }

    private Map<Integer, StoredItem> loadExisting(Connection connection, UUID ownerId) throws SQLException {
        Map<Integer, StoredItem> existing = new HashMap<>();
        String sql = "SELECT slot_index, item_blob, item_amount FROM " + table + " WHERE owner_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Number amount = (Number) result.getObject("item_amount");
                    existing.put(result.getInt("slot_index"), new StoredItem(result.getBytes("item_blob"), amount == null ? 1 : amount.intValue()));
                }
            }
        }
        return existing;
    }

    private List<StoredItem> serializeItems(List<ItemStack> items) throws IOException {
        List<StoredItem> serialized = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            int amount = item.getAmount();
            ItemStack data = item.clone();
            data.setAmount(1);
            serialized.add(new StoredItem(ItemUtils.toBytes(data), amount));
        }
        return serialized;
    }

    private synchronized boolean ensureInitialized() {
        if (initialized) return true;
        if (initializationFailed) return false;
        String sql = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "owner_uuid VARCHAR(36) NOT NULL,"
                + "slot_index INT NOT NULL,"
                + "item_blob BLOB NOT NULL,"
                + "item_amount INT NULL,"
                + "PRIMARY KEY (owner_uuid, slot_index)"
                + ")";

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            ensureAmountColumn(statement);
            initialized = true;
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create SQL storage table: " + e.getMessage());
            initializationFailed = true;
            return false;
        }
    }

    private void rollback(Connection connection, String action) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            plugin.getLogger().severe("Failed to rollback SQL " + action + ": " + rollbackException.getMessage());
        }
    }

    private void ensureAmountColumn(Statement statement) throws SQLException {
        try (ResultSet ignored = statement.executeQuery("SELECT item_amount FROM " + table + " WHERE 1 = 0")) {
            return;
        } catch (SQLException missingColumn) {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN item_amount INT NULL");
        }
    }

    private record StoredItem(byte[] data, int amount) {
        @Override
        public boolean equals(Object other) {
            return other instanceof StoredItem item && amount == item.amount && Arrays.equals(data, item.data);
        }

        @Override
        public int hashCode() {
            return 31 * amount + Arrays.hashCode(data);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
