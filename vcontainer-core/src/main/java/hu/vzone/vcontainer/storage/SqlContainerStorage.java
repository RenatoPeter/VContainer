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
    private final VContainer plugin;
    private final HikariDataSource dataSource;
    private final String table;
    private final StorageSettings.StorageType type;

    public SqlContainerStorage(VContainer plugin, StorageSettings settings) {
        this.plugin = plugin;
        this.table = settings.prefix() + "player_data";
        this.type = settings.type();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(settings.buildJdbcUrl(plugin.getDataFolder()));
        config.setMaximumPoolSize(settings.poolSize());
        config.setPoolName("VContainer-" + settings.type().name());

        String driverClass = settings.resolvedDriverClass();
        if (!driverClass.isBlank()) config.setDriverClassName(driverClass);
        if (settings.type() != StorageSettings.StorageType.H2) {
            config.setUsername(settings.username());
            config.setPassword(settings.password());
        }

        this.dataSource = new HikariDataSource(config);
        createTable();
    }

    @Override
    public Map<UUID, List<ItemStack>> loadAll() {
        Map<UUID, List<ItemStack>> containers = new HashMap<>();
        String sql = "SELECT owner_uuid, item_blob FROM " + table + " ORDER BY owner_uuid, slot_index";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                try {
                    UUID ownerId = UUID.fromString(result.getString("owner_uuid"));
                    ItemStack item = ItemUtils.fromBytes(result.getBytes("item_blob"));
                    if (item == null || item.getType().isAir()) continue;
                    containers.computeIfAbsent(ownerId, ignored -> new ArrayList<>()).add(item);
                } catch (Exception rowException) {
                    plugin.getLogger().severe("Skipping corrupt SQL container row: owner="
                            + result.getString("owner_uuid")
                            + " error=" + rowException.getMessage());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load SQL containers: " + e.getMessage());
        }

        return containers;
    }

    @Override
    public boolean save(UUID ownerId, List<ItemStack> items) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try {
                Map<Integer, byte[]> existing = loadExisting(connection, ownerId);
                List<byte[]> desired = serializeItems(items);

                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE owner_uuid = ? AND slot_index = ?");
                     PreparedStatement upsert = connection.prepareStatement(upsertSql())) {
                    for (Map.Entry<Integer, byte[]> entry : existing.entrySet()) {
                        if (entry.getKey() >= desired.size() || !Arrays.equals(entry.getValue(), desired.get(entry.getKey()))) {
                            delete.setString(1, ownerId.toString());
                            delete.setInt(2, entry.getKey());
                            delete.addBatch();
                        }
                    }

                    for (int slot = 0; slot < desired.size(); slot++) {
                        byte[] blob = desired.get(slot);
                        if (Arrays.equals(existing.get(slot), blob)) continue;

                        upsert.setString(1, ownerId.toString());
                        upsert.setInt(2, slot);
                        upsert.setBytes(3, blob);
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
            return "MERGE INTO " + table + " (owner_uuid, slot_index, item_blob) KEY(owner_uuid, slot_index) VALUES (?, ?, ?)";
        }
        return "INSERT INTO " + table + " (owner_uuid, slot_index, item_blob) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE item_blob = VALUES(item_blob)";
    }

    private Map<Integer, byte[]> loadExisting(Connection connection, UUID ownerId) throws SQLException {
        Map<Integer, byte[]> existing = new HashMap<>();
        String sql = "SELECT slot_index, item_blob FROM " + table + " WHERE owner_uuid = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    existing.put(result.getInt("slot_index"), result.getBytes("item_blob"));
                }
            }
        }
        return existing;
    }

    private List<byte[]> serializeItems(List<ItemStack> items) throws IOException {
        List<byte[]> serialized = new ArrayList<>();
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            serialized.add(ItemUtils.toBytes(item.clone()));
        }
        return serialized;
    }

    private void createTable() {
        String sql = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "owner_uuid VARCHAR(36) NOT NULL,"
                + "slot_index INT NOT NULL,"
                + "item_blob BLOB NOT NULL,"
                + "PRIMARY KEY (owner_uuid, slot_index)"
                + ")";

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to create SQL storage table: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
