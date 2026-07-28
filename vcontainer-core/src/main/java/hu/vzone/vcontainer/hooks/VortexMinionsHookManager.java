package hu.vzone.vcontainer.hooks;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.listeners.VortexMinionsHookListener;
import hu.vzone.vcontainer.managers.ContainerManager;
import hu.vzone.vcontainer.managers.StorageBlockManager;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

public final class VortexMinionsHookManager {
    private static final String PLUGIN_NAME = "VortexMinions";

    private final VContainer plugin;
    private final ContainerManager containerManager;
    private final StorageBlockManager storageBlockManager;
    private Listener hookListener;

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
        if (hookListener != null) {
            return;
        }

        hookListener = new VortexMinionsHookListener(containerManager, storageBlockManager);
        plugin.getServer().getPluginManager().registerEvents(hookListener, plugin);
        plugin.getLogger().info("Hooked into VortexMinions.");
    }

    public synchronized void unhook() {
        unhook(true);
    }

    public synchronized void shutdown() {
        unhook(false);
    }

    private void unhook(boolean log) {
        if (hookListener == null) {
            return;
        }
        HandlerList.unregisterAll(hookListener);
        hookListener = null;
        if (log) {
            plugin.getLogger().info("Unhooked from VortexMinions.");
        }
    }
}
