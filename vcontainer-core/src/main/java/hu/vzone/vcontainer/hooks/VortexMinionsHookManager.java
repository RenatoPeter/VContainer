package hu.vzone.vcontainer.hooks;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.listeners.VortexMinionsHookListener;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.managers.StorageBlockManager;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class VortexMinionsHookManager {
    private static final String PLUGIN_NAME = "VortexMinions";

    private final VContainer plugin;
    private final ContainerManager containerManager;
    private final StorageBlockManager storageBlockManager;
    private Listener hookListener;
    private Plugin hookedPlugin;
    private BukkitTask healthCheckTask;

    public VortexMinionsHookManager(
            VContainer plugin,
            ContainerManager containerManager,
            StorageBlockManager storageBlockManager
    ) {
        this.plugin = plugin;
        this.containerManager = containerManager;
        this.storageBlockManager = storageBlockManager;
    }

    public synchronized void refreshHook() {
        Plugin vortexMinions = plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME);
        if (vortexMinions == null || !vortexMinions.isEnabled()) {
            unhook(false);
            return;
        }
        if (hookListener != null && hookedPlugin == vortexMinions) {
            return;
        }

        unhook(false);

        hookListener = new VortexMinionsHookListener(containerManager, storageBlockManager);
        plugin.getServer().getPluginManager().registerEvents(hookListener, plugin);
        hookedPlugin = vortexMinions;
        plugin.getLogger().info("Hooked into VortexMinions.");
    }

    /** Covers plugin managers that replace a plugin instance without a reliable lifecycle event. */
    public synchronized void startHealthCheck() {
        if (healthCheckTask != null && !healthCheckTask.isCancelled()) return;
        healthCheckTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshHook, 100L, 100L);
    }

    public synchronized void unhook() {
        unhook(true);
    }

    public synchronized void shutdown() {
        unhook(false);
        if (healthCheckTask != null) {
            healthCheckTask.cancel();
            healthCheckTask = null;
        }
    }

    private void unhook(boolean log) {
        if (hookListener == null) {
            hookedPlugin = null;
            return;
        }
        HandlerList.unregisterAll(hookListener);
        hookListener = null;
        hookedPlugin = null;
        if (log) {
            plugin.getLogger().info("Unhooked from VortexMinions.");
        }
    }
}
