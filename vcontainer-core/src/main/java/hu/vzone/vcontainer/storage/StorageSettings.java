package hu.vzone.vcontainer.storage;

import hu.vzone.vcontainer.VContainer;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;

public record StorageSettings(
        StorageType type,
        String hostname,
        int port,
        String username,
        String password,
        String database,
        int poolSize,
        boolean useSsl,
        String jdbcUrl,
        String driverClass,
        String prefix
) {
    public static StorageSettings from(VContainer plugin) {
        FileConfiguration config = plugin.getDatabaseConfig();
        String rawType = config.getString("storage.Type", "LOCAL").toUpperCase();
        StorageType type;
        try {
            type = StorageType.valueOf(rawType);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown storage.Type '" + rawType + "', falling back to LOCAL.");
            type = StorageType.LOCAL;
        }

        return new StorageSettings(
                type,
                config.getString("storage.Hostname", "127.0.0.1"),
                config.getInt("storage.Port", type == StorageType.MARIADB || type == StorageType.MYSQL ? 3306 : 0),
                config.getString("storage.Username", "minecraft"),
                config.getString("storage.Password", ""),
                config.getString("storage.Database", "minecraft"),
                Math.max(1, config.getInt("storage.Pool Size", 5)),
                config.getBoolean("storage.Use SSL", false),
                config.getString("storage.Jdbc Url", ""),
                config.getString("storage.Driver Class", ""),
                sanitizePrefix(config.getString("storage.Prefix", "vcontainer_"))
        );
    }

    public String buildJdbcUrl(File dataFolder) {
        if (jdbcUrl != null && !jdbcUrl.isBlank()) return jdbcUrl;
        return switch (type) {
            case MYSQL -> "jdbc:mysql://" + hostname + ":" + port + "/" + database
                    + "?useSSL=" + useSsl + "&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=10000";
            case MARIADB -> "jdbc:mariadb://" + hostname + ":" + port + "/" + database
                    + "?useSsl=" + useSsl + "&connectTimeout=5000&socketTimeout=10000";
            case H2 -> "jdbc:h2:" + new File(dataFolder, database).getAbsolutePath() + ";MODE=MySQL";
            case LOCAL -> "";
        };
    }

    public String resolvedDriverClass() {
        if (driverClass != null && !driverClass.isBlank()) return driverClass;
        return switch (type) {
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case MARIADB -> "org.mariadb.jdbc.Driver";
            case H2 -> "org.h2.Driver";
            case LOCAL -> "";
        };
    }

    private static String sanitizePrefix(String prefix) {
        String safe = prefix == null ? "vcontainer_" : prefix.replaceAll("[^A-Za-z0-9_]", "");
        return safe.isBlank() ? "vcontainer_" : safe;
    }

    public enum StorageType {
        LOCAL,
        MYSQL,
        MARIADB,
        H2
    }
}
