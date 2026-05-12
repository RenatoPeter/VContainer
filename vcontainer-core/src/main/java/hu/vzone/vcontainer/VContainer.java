package hu.vzone.vcontainer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hu.vzone.vcontainer.api.VContainerAPI;
import hu.vzone.vcontainer.api.impl.VContainerAPIImpl;
import hu.vzone.vcontainer.commands.ContainerAdminCommand;
import hu.vzone.vcontainer.commands.ContainerCommand;
import hu.vzone.vcontainer.listeners.ContainerListener;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.managers.StorageBlockManager;
import hu.vzone.vcontainer.storage.StorageSettings;
import hu.vzone.vcontainer.utils.ServerVersionSupport;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Map;

public final class VContainer extends JavaPlugin {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private static VContainer instance;
    private static VContainerAPI api;

    private File messageConfigFile;
    private FileConfiguration messageConfig;
    private File databaseConfigFile;
    private FileConfiguration databaseConfig;
    private final Map<String, FileConfiguration> menuConfigs = new HashMap<>();
    private Gson gson;
    private File playerDataFolder;
    private ContainerManager containerManager;
    private StorageBlockManager storageBlockManager;

    @Override
    public void onEnable() {
        instance = this;
        gson = new GsonBuilder().serializeNulls().create();

        logServerVersionSupport();

        saveDefaultConfig();
        updateDefaultConfig();
        createDatabaseConfig();
        createMessageConfig();
        createMenuConfigs();

        playerDataFolder = new File(getStorageFolder(), "player_data");
        if (isLocalStorageBackend() && !playerDataFolder.exists() && !playerDataFolder.mkdirs()) {
            getLogger().warning("Could not create player data folder: " + playerDataFolder.getAbsolutePath());
        }

        containerManager = new ContainerManager(this);
        storageBlockManager = new StorageBlockManager(this, containerManager);
        api = new VContainerAPIImpl(this, containerManager, storageBlockManager);

        Bukkit.getServicesManager().register(VContainerAPI.class, api, this, ServicePriority.Normal);

        getCommand("container").setExecutor(new ContainerCommand(this, containerManager));
        ContainerAdminCommand adminCommand = new ContainerAdminCommand(this, containerManager, storageBlockManager);
        getCommand("vcontainer").setExecutor(adminCommand);
        getCommand("vcontainer").setTabCompleter(adminCommand);
        Bukkit.getPluginManager().registerEvents(new ContainerListener(containerManager, storageBlockManager), this);

        getLogger().info("VContainer v" + getDescription().getVersion() + " enabled successfully.");
    }

    private void logServerVersionSupport() {
        String currentVersion = ServerVersionSupport.currentVersion();
        if (ServerVersionSupport.isSupported()) {
            getLogger().info("Detected supported Minecraft version: " + currentVersion);
            return;
        }

        getLogger().warning("Detected unsupported Minecraft version: " + currentVersion);
        getLogger().warning("Supported versions: " + ServerVersionSupport.supportedVersionsText());
    }

    @Override
    public void onDisable() {
        if (containerManager != null) {
            containerManager.flushAllSync();
        }
        if (storageBlockManager != null) {
            storageBlockManager.shutdown();
        }

        HandlerList.unregisterAll(this);

        if (api != null) {
            Bukkit.getServicesManager().unregister(VContainerAPI.class, api);
        }

        api = null;
        instance = null;
        getLogger().info("VContainer disabled.");
    }

    public static VContainer getInstance() {
        return instance;
    }

    public Gson getGson() {
        return gson;
    }

    public File getPlayerDataFolder() {
        return playerDataFolder;
    }

    public File getStorageFolder() {
        return new File(getDataFolder(), "storage");
    }

    public boolean isLocalStorageBackend() {
        return StorageSettings.from(this).type() == StorageSettings.StorageType.LOCAL;
    }

    public ContainerManager getContainerManager() {
        return containerManager;
    }

    public StorageBlockManager getStorageBlockManager() {
        return storageBlockManager;
    }

    public static VContainerAPI getAPI() {
        return api;
    }

    public FileConfiguration getMessageConfig() {
        return messageConfig;
    }

    public FileConfiguration getDatabaseConfig() {
        return databaseConfig;
    }

    public FileConfiguration getMenuConfig(String name) {
        return menuConfigs.get(name);
    }

    private void updateDefaultConfig() {
        getConfig().options().copyDefaults(true);
        getConfig().set("container-options.sorting", null);
        getConfig().set("buttons", null);
        getConfig().set("player-data-folder", null);
        saveConfig();
    }

    private void createMessageConfig() {
        messageConfigFile = new File(getDataFolder(), "messages.yml");
        if (!messageConfigFile.exists()) {
            messageConfigFile.getParentFile().mkdirs();
            saveResource("messages.yml", false);
        }

        messageConfig = new YamlConfiguration();
        try {
            messageConfig.load(messageConfigFile);
            try (InputStream stream = getResource("messages.yml")) {
                if (stream != null) {
                    YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(
                            stream,
                            StandardCharsets.UTF_8
                    ));
                    messageConfig.setDefaults(defaults);
                    messageConfig.options().copyDefaults(true);
                    messageConfig.save(messageConfigFile);
                }
            }
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().severe("Failed to load messages.yml: " + e.getMessage());
        }
    }

    private void createDatabaseConfig() {
        databaseConfigFile = new File(getDataFolder(), "database.yml");
        if (!databaseConfigFile.exists()) {
            databaseConfigFile.getParentFile().mkdirs();
            saveResource("database.yml", false);
        }

        databaseConfig = YamlConfiguration.loadConfiguration(databaseConfigFile);
        try (InputStream stream = getResource("database.yml")) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                databaseConfig.setDefaults(defaults);
                databaseConfig.options().copyDefaults(true);
                databaseConfig.save(databaseConfigFile);
            }
        } catch (IOException e) {
            getLogger().severe("Failed to load database.yml: " + e.getMessage());
        }
    }

    private void createMenuConfigs() {
        menuConfigs.clear();
        loadMenuConfig("container");
        loadMenuConfig("members");
    }

    public void reloadMenuConfigs() {
        createMenuConfigs();
    }

    private void loadMenuConfig(String name) {
        File file = new File(getDataFolder(), "menus/" + name + ".yml");
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            saveResource("menus/" + name + ".yml", false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        try (InputStream stream = getResource("menus/" + name + ".yml")) {
            if (stream != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
                config.options().copyDefaults(true);
                config.save(file);
            }
        } catch (IOException e) {
            getLogger().severe("Failed to load menus/" + name + ".yml: " + e.getMessage());
        }
        menuConfigs.put(name, config);
    }

    public void reloadMessageConfig() {
        if (messageConfigFile.exists()) {
            messageConfig = YamlConfiguration.loadConfiguration(messageConfigFile);
            return;
        }

        Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[VContainer] messages.yml not found.");
        createMessageConfig();
    }

    public String getPrefix() {
        return translateHexColorCodes(ChatColor.translateAlternateColorCodes('&', getMessageConfig().getString(
                "prefix",
                "&#1898FFV&#1898FFC&#1898FFo&#1898FFn&#1898FFt&#1898FFa&#12A2FFi&#0CADFFn&#06B7FFe&#00C1FFr &8>&f"
        )));
    }

    public static String formatMessage(String message) {
        if (message == null) return "";

        String prefix = getInstance() == null ? "" : getInstance().getPrefix();
        return translateHexColorCodes(ChatColor.translateAlternateColorCodes('&', message.replace("{prefix}", prefix)));
    }

    private static String translateHexColorCodes(final String message) {
        final char colorChar = ChatColor.COLOR_CHAR;
        final Matcher matcher = HEX_PATTERN.matcher(message);
        final StringBuffer buffer = new StringBuffer(message.length() + 4 * 8);

        while (matcher.find()) {
            final String group = matcher.group(1);

            matcher.appendReplacement(buffer, colorChar + "x"
                    + colorChar + group.charAt(0) + colorChar + group.charAt(1)
                    + colorChar + group.charAt(2) + colorChar + group.charAt(3)
                    + colorChar + group.charAt(4) + colorChar + group.charAt(5));
        }

        return matcher.appendTail(buffer).toString();
    }
}
