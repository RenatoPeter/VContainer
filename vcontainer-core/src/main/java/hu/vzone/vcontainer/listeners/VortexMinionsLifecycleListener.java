package hu.vzone.vcontainer.listeners;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.hooks.VortexMinionsHookManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

public final class VortexMinionsLifecycleListener implements Listener {
    private static final String PLUGIN_NAME = "VortexMinions";

    private final VContainer plugin;
    private final VortexMinionsHookManager hookManager;

    public VortexMinionsLifecycleListener(VContainer plugin, VortexMinionsHookManager hookManager) {
        this.plugin = plugin;
        this.hookManager = hookManager;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (!PLUGIN_NAME.equalsIgnoreCase(event.getPlugin().getName())) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, hookManager::refreshHook);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!PLUGIN_NAME.equalsIgnoreCase(event.getPlugin().getName())) {
            return;
        }
        hookManager.unhook();
    }
}
