package hu.vzone.vcontainer.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.utils.ItemUtils;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SqlContainerStorage implements ContainerStorage {
    private final VContainer plugin;
    private final HikariDataSource dataSource;
    private final String table;

    public SqlContainerStorage(VContainer plugin, StorageSettings settings) {
        this.plugin = plugin;
        this.table = settings.prefix() + "player_data";

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
                UUID ownerId = UUID.fromString(result.getString("owner_uuid"));
                ItemStack item = ItemUtils.fromBytes(result.getBytes("item_blob"));
                if (item == null || item.getType().isAir()) continue;
                containers.computeIfAbsent(ownerId, ignored -> new ArrayList<>()).add(item);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load SQL containers: " + e.getMessage());
        }

        return containers;
    }

    @Override
    public void save(UUID ownerId, List<ItemStack> items) {
        String deleteSql = "DELETE FROM " + table + " WHERE owner_uuid = ?";
        String insertSql = "INSERT INTO " + table + " (owner_uuid, slot_index, item_blob) VALUES (?, ?, ?)";

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try (PreparedStatement delete = connection.prepareStatement(deleteSql)) {
                delete.setString(1, ownerId.toString());
                delete.executeUpdate();
            }

            try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                int slot = 0;
                for (ItemStack item : items) {
                    if (item == null || item.getType().isAir()) continue;

                    ItemStack snapshot = item.clone();
                    insert.setString(1, ownerId.toString());
                    insert.setInt(2, slot++);
                    insert.setBytes(3, ItemUtils.toBytes(snapshot));
                    insert.addBatch();
                }
                insert.executeBatch();
            }

            connection.commit();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to save SQL container for " + ownerId + ": " + e.getMessage());
        }
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
