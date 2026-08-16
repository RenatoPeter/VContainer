package hu.vzone.vcontainer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hu.vzone.vcontainer.api.VContainerAPI;
import hu.vzone.vcontainer.api.impl.VContainerAPIImpl;
import hu.vzone.vcontainer.commands.ContainerAdminCommand;
import hu.vzone.vcontainer.commands.ContainerCommand;
import hu.vzone.vcontainer.gui.ContainerGUI;
import hu.vzone.vcontainer.hooks.VortexMinionsHookManager;
import hu.vzone.vcontainer.listeners.ContainerListener;
import hu.vzone.vcontainer.listeners.VortexMinionsLifecycleListener;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.sell.SellService;
import hu.vzone.vcontainer.managers.StorageBlockManager;
import hu.vzone.vcontainer.storage.StorageSettings;
import hu.vzone.vcontainer.utils.ConfigUpdater;
import hu.vzone.vcontainer.utils.PlaceholderHook;
import hu.vzone.vcontainer.utils.ServerVersionSupport;
import hu.vzone.vcontainer.utils.AuditLogger;
import hu.vzone.vcontainer.utils.SkinProvider;
import hu.vzone.vcontainer.utils.UpdateChecker;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Map;

public final class VContainer extends JavaPlugin {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern AMPERSAND_HEX_PATTERN = Pattern.compile("(?i)&x&([0-9a-f])&([0-9a-f])&([0-9a-f])&([0-9a-f])&([0-9a-f])&([0-9a-f])");
    private static final Pattern SECTION_HEX_PATTERN = Pattern.compile("(?i)§x§([0-9a-f])§([0-9a-f])§([0-9a-f])§([0-9a-f])§([0-9a-f])§([0-9a-f])");
    private static final Pattern MINI_MESSAGE_TAG_PATTERN = Pattern.compile("<[^\\s<>]+(:[^<>]*)?>");
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();
    private static final char STYLE_SENTINEL = '\uE000';

    private static VContainer instance;
    private static VContainerAPI api;

    private File messageConfigFile;
    private FileConfiguration messageConfig;
    private File databaseConfigFile;
    private FileConfiguration databaseConfig;
    private FileConfiguration migrationConfig;
    private File pricesConfigFile;
    private FileConfiguration pricesConfig;
    private final Map<String, FileConfiguration> menuConfigs = new HashMap<>();
    private Gson gson;
    private File playerDataFolder;
    private ContainerManager containerManager;
    private StorageBlockManager storageBlockManager;
    private SellService sellService;
    private UpdateChecker updateChecker;
    private VortexMinionsHookManager vortexMinionsHookManager;
    private volatile boolean restartRequired;
    private volatile String restartReason = "";

    @Override
    public void onEnable() {
        instance = this;
        gson = new GsonBuilder().serializeNulls().create();

        logServerVersionSupport();

        saveDefaultConfig();
        updateDefaultConfig();
        createDatabaseConfig();
        createMigrationConfig();
        createPricesConfig();
        createMessageConfig();
        createMenuConfigs();

        playerDataFolder = new File(getStorageFolder(), "player_data");
        if (isLocalStorageBackend() && !playerDataFolder.exists() && !playerDataFolder.mkdirs()) {
            getLogger().warning("Could not create player data folder: " + playerDataFolder.getAbsolutePath());
        }

        containerManager = new ContainerManager(this);
        storageBlockManager = new StorageBlockManager(this, containerManager);
        sellService = new SellService(this);
        updateChecker = new UpdateChecker(this);
        vortexMinionsHookManager = new VortexMinionsHookManager(this, containerManager, storageBlockManager);
        api = new VContainerAPIImpl(this, containerManager, storageBlockManager);

        Bukkit.getServicesManager().register(VContainerAPI.class, api, this, ServicePriority.Normal);

        getCommand("container").setExecutor(new ContainerCommand(this, containerManager));
        ContainerAdminCommand adminCommand = new ContainerAdminCommand(this, containerManager, storageBlockManager);
        getCommand("vcontainer").setExecutor(adminCommand);
        getCommand("vcontainer").setTabCompleter(adminCommand);
        Bukkit.getPluginManager().registerEvents(new ContainerListener(containerManager, storageBlockManager), this);
        Bukkit.getPluginManager().registerEvents(new VortexMinionsLifecycleListener(this, vortexMinionsHookManager), this);
        vortexMinionsHookManager.refreshHook();
        updateChecker.checkAsync();

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
        if (sellService != null) {
            sellService.shutdown();
        }
        if (containerManager != null) {
            containerManager.flushAllSync();
        }
        if (storageBlockManager != null) {
            storageBlockManager.shutdown();
        }
        if (vortexMinionsHookManager != null) {
            vortexMinionsHookManager.shutdown();
        }

        HandlerList.unregisterAll(this);

        if (api != null) {
            Bukkit.getServicesManager().unregister(VContainerAPI.class, api);
        }

        ContainerGUI.shutdown();
        SkinProvider.shutdown();
        AuditLogger.shutdown();
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

    public boolean isRestartRequired() {
        return restartRequired;
    }

    public String getRestartReason() {
        return restartReason;
    }

    public void enterRestartRequiredMode(String reason) {
        restartRequired = true;
        restartReason = reason == null ? "" : reason;
        if (containerManager != null) containerManager.setPersistenceSuspended(true);
        if (storageBlockManager != null) storageBlockManager.setPersistenceSuspended(true);
        getLogger().warning("VContainer is now in restart-required mode: " + restartReason);
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

    public FileConfiguration getMigrationConfig() {
        return migrationConfig;
    }

    public FileConfiguration getPricesConfig() {
        return pricesConfig;
    }

    public SellService getSellService() {
        return sellService;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public FileConfiguration getMenuConfig(String name) {
        return menuConfigs.get(name);
    }

    private void updateDefaultConfig() {
        File configFile = new File(getDataFolder(), "config.yml");
        ConfigUpdater.load(this, "config.yml", configFile);
        reloadConfig();
    }

    public void reloadMainConfig() {
        updateDefaultConfig();
        if (containerManager != null) {
            containerManager.reloadRuntimeSettings();
        }
        if (sellService != null) {
            sellService.reload();
        }
    }

    private void createMessageConfig() {
        messageConfigFile = new File(getDataFolder(), "messages.yml");
        messageConfig = ConfigUpdater.load(this, "messages.yml", messageConfigFile);
    }

    private void createDatabaseConfig() {
        databaseConfigFile = new File(getDataFolder(), "database.yml");
        databaseConfig = ConfigUpdater.load(this, "database.yml", databaseConfigFile);
    }

    private void createMigrationConfig() {
        File migrationConfigFile = new File(getDataFolder(), "migration.yml");
        migrationConfig = ConfigUpdater.load(this, "migration.yml", migrationConfigFile);
    }

    private void createPricesConfig() {
        pricesConfigFile = new File(getDataFolder(), "prices.yml");
        if (!pricesConfigFile.exists()) {
            pricesConfigFile.getParentFile().mkdirs();
            saveResource("prices.yml", false);
        }
        pricesConfig = YamlConfiguration.loadConfiguration(pricesConfigFile);
    }

    private void createMenuConfigs() {
        menuConfigs.clear();
        loadMenuConfig("container");
        loadMenuConfig("confirm");
        loadMenuConfig("members");
    }

    public void reloadMenuConfigs() {
        createMenuConfigs();
    }

    private void loadMenuConfig(String name) {
        File file = new File(getDataFolder(), "menus/" + name + ".yml");
        YamlConfiguration config = ConfigUpdater.load(this, "menus/" + name + ".yml", file);
        menuConfigs.put(name, config);
    }

    public void reloadMessageConfig() {
        messageConfig = ConfigUpdater.load(this, "messages.yml", messageConfigFile);
    }

    public void reloadDatabaseConfig() {
        databaseConfig = ConfigUpdater.load(this, "database.yml", databaseConfigFile);
    }

    public void reloadMigrationConfig() {
        File file = new File(getDataFolder(), "migration.yml");
        migrationConfig = ConfigUpdater.load(this, "migration.yml", file);
    }

    public void reloadPricesConfig() {
        if (pricesConfigFile.exists()) {
            pricesConfig = YamlConfiguration.loadConfiguration(pricesConfigFile);
            if (sellService != null) {
                sellService.reload();
            }
            return;
        }

        Bukkit.getConsoleSender().sendMessage(ChatColor.RED + "[VContainer] prices.yml not found.");
        createPricesConfig();
    }

    public String getPrefix() {
        return formatColors(getMessageConfig().getString(
                "prefix",
                "<gradient:#1378FF:#60BFFB>VContainer</gradient> &8»&7"
        ));
    }

    public static String formatMessage(String message) {
        return formatMessage(null, message);
    }

    public static String formatMessage(OfflinePlayer player, String message) {
        if (message == null) return "";

        String prefix = getInstance() == null ? "" : getInstance().getPrefix();
        String formatted = message.replace("{prefix}", prefix);
        formatted = PlaceholderHook.apply(player, formatted);
        return formatColors(formatted);
    }

    private static String formatColors(String message) {
        if (message == null) return "";
        if (containsMiniMessageTag(message)) {
            try {
                Component component = MINI_MESSAGE.deserialize(legacyToMiniMessage(message) + STYLE_SENTINEL);
                return LEGACY_SECTION.serialize(component).replace(String.valueOf(STYLE_SENTINEL), "");
            } catch (RuntimeException ignored) {
            }
        }
        return translateHexColorCodes(ChatColor.translateAlternateColorCodes('&', message));
    }

    private static boolean containsMiniMessageTag(String message) {
        return MINI_MESSAGE_TAG_PATTERN.matcher(message).find();
    }

    private static String legacyToMiniMessage(String message) {
        String converted = SECTION_HEX_PATTERN.matcher(message).replaceAll("<#$1$2$3$4$5$6>");
        converted = AMPERSAND_HEX_PATTERN.matcher(converted).replaceAll("<#$1$2$3$4$5$6>");
        converted = HEX_PATTERN.matcher(converted).replaceAll("<#$1>");

        StringBuilder builder = new StringBuilder(converted.length());
        for (int i = 0; i < converted.length(); i++) {
            char current = converted.charAt(i);
            if ((current == '&' || current == ChatColor.COLOR_CHAR) && i + 1 < converted.length()) {
                String tag = legacyCodeToMiniMessageTag(Character.toLowerCase(converted.charAt(i + 1)));
                if (tag != null) {
                    builder.append(tag);
                    i++;
                    continue;
                }
            }
            builder.append(current);
        }
        return builder.toString();
    }

    private static String legacyCodeToMiniMessageTag(char code) {
        return switch (code) {
            case '0' -> "<black>";
            case '1' -> "<dark_blue>";
            case '2' -> "<dark_green>";
            case '3' -> "<dark_aqua>";
            case '4' -> "<dark_red>";
            case '5' -> "<dark_purple>";
            case '6' -> "<gold>";
            case '7' -> "<gray>";
            case '8' -> "<dark_gray>";
            case '9' -> "<blue>";
            case 'a' -> "<green>";
            case 'b' -> "<aqua>";
            case 'c' -> "<red>";
            case 'd' -> "<light_purple>";
            case 'e' -> "<yellow>";
            case 'f' -> "<white>";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> null;
        };
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
