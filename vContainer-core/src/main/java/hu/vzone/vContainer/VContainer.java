package hu.vzone.vContainer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hu.vzone.vContainer.api.impl.VContainerAPIImpl;
import hu.vzone.vContainer.commands.ContainerAdminCommand;
import hu.vzone.vContainer.commands.ContainerCommand;
import hu.vzone.vContainer.listeners.ContainerListener;
import hu.vzone.vContainer.listeners.TesztL;
import hu.vzone.vContainer.managers.ContainerManager;
import hu.vzone.vcontainer.api.VContainerAPI;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class VContainer extends JavaPlugin {

    private static VContainer instance;
    private Gson gson;
    private File playerDataFolder;
    private FileConfiguration customConfig;
    private ContainerManager containerManager;
    private static VContainerAPI api;

    @Override
    public void onEnable() {
        instance = this;
        gson = new GsonBuilder().serializeNulls().create();


        saveDefaultConfig();
        loadCustomConfig();


        playerDataFolder = new File(getDataFolder(), "player_data");
        if (!playerDataFolder.exists()) playerDataFolder.mkdirs();


        this.containerManager = new ContainerManager(this);

        api = new VContainerAPIImpl(containerManager);

        // Commands
        getCommand("container").setExecutor(new ContainerCommand(containerManager));
        getCommand("vcontainer").setExecutor(new ContainerAdminCommand(this, containerManager));


        // Events
        Bukkit.getPluginManager().registerEvents(new ContainerListener(containerManager), this);
        Bukkit.getPluginManager().registerEvents(new TesztL(containerManager), this);


        getLogger().info("VContainer v" + getDescription().getVersion() + " enabled");
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        // Plugin shutdown logic
    }

    public static VContainer getInstance() { return instance; }
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
}
