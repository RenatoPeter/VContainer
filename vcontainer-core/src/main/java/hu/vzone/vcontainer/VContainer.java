package hu.vzone.vcontainer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hu.vzone.vcontainer.api.impl.VContainerAPIImpl;
import hu.vzone.vcontainer.commands.ContainerAdminCommand;
import hu.vzone.vcontainer.commands.ContainerCommand;
import hu.vzone.vcontainer.listeners.ContainerListener;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.api.VContainerAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VContainer extends JavaPlugin {

    private File messageConfigFile;
    private FileConfiguration messageConfig;
    private static VContainer instance;
    private Gson gson;
    private File playerDataFolder;
    private FileConfiguration customConfig;
    private ContainerManager containerManager;
    private static VContainerAPI api;
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    @Override
    public void onEnable() {
        // FONTOS: ez legyen az ELSŐ SOR
        instance = this;

        // GSON
        gson = new GsonBuilder().serializeNulls().create();

        // Configok betöltése
        saveDefaultConfig();
        loadCustomConfig();
        createMessageConfig();

        // Player data mappa
        playerDataFolder = new File(getDataFolder(), "player_data");
        if (!playerDataFolder.exists()) playerDataFolder.mkdirs();

        // Manager és API inicializálás
        this.containerManager = new ContainerManager(this);
        api = new VContainerAPIImpl(containerManager);

        // --- REGISZTRÁLÁS ---
        Bukkit.getServicesManager().register(VContainerAPI.class, api, this, org.bukkit.plugin.ServicePriority.Normal);

        // Parancsok
        getCommand("container").setExecutor(new ContainerCommand(this, containerManager));
        getCommand("vcontainer").setExecutor(new ContainerAdminCommand(this, containerManager));

        // Események
        Bukkit.getPluginManager().registerEvents(new ContainerListener(containerManager, this), this);

        getLogger().info("✅ VContainer v" + getDescription().getVersion() + " enabled successfully!");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        api = null; // <--- biztonság kedvéért reseteljük
        instance = null;
        getLogger().info("🟥 VContainer disabled.");
    }


    public static VContainer getInstance() {
        return instance;
    }
    public Gson getGson() { return gson; }
    public File getPlayerDataFolder() { return playerDataFolder; }
    public ContainerManager getContainerManager() { return containerManager; }
    public static VContainerAPI getAPI() {
        return api;
    }

    private void loadCustomConfig() {
        File file = new File(getDataFolder(), "config.yml");
        customConfig = YamlConfiguration.loadConfiguration(file);
    }

    public FileConfiguration getMessageConfig() {
        return this.messageConfig;
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
        } catch (IOException | InvalidConfigurationException e) {
            e.printStackTrace();
        }
    }
    public void reloadMessageConfig() {
        if (messageConfigFile.exists()) {
            messageConfig = YamlConfiguration.loadConfiguration(messageConfigFile);
        } else {
            Bukkit.getServer().getConsoleSender().sendMessage("§c[ERROR] VContainer | messages.yml not found.");
            createMessageConfig();
        }
    }

    public String getPrefix(){
        return translateHexColorCodes(ChatColor.translateAlternateColorCodes('&', getMessageConfig().getString("prefix", "&#1898FFV&#1898FFC&#1898FFo&#1898FFn&#1898FFt&#1898FFa&#12A2FFi&#0CADFFn&#06B7FFe&#00C1FFr &8»&f")));
    }

    public static String formatMessage(String message) {
        return translateHexColorCodes(ChatColor.translateAlternateColorCodes('&', message.replace("{prefix}", getInstance().getPrefix())));
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
