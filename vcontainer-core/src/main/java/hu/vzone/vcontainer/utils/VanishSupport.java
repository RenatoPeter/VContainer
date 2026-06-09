package hu.vzone.vcontainer.utils;

import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.lang.reflect.Method;

public final class VanishSupport {

    private VanishSupport() {
    }

    public static boolean canSee(Player viewer, Player target) {
        if (viewer == null || target == null) return false;
        if (viewer.getUniqueId().equals(target.getUniqueId())) return true;

        Boolean premiumOrSuper = canSeeViaMyzelYamApi(viewer, target);
        if (premiumOrSuper != null) {
            return premiumOrSuper;
        }

        Boolean essentials = canSeeViaEssentials(viewer, target);
        if (essentials != null) {
            return essentials;
        }

        if (hasVanishedMetadata(target)) {
            return viewer.canSee(target);
        }

        return true;
    }

    private static Boolean canSeeViaMyzelYamApi(Player viewer, Player target) {
        PluginManager pluginManager = viewer.getServer().getPluginManager();
        Plugin plugin = pluginManager.getPlugin("PremiumVanish");
        if (plugin == null || !plugin.isEnabled()) {
            plugin = pluginManager.getPlugin("SuperVanish");
        }
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }

        try {
            Class<?> apiClass = Class.forName("de.myzelyam.api.vanish.VanishAPI", true, plugin.getClass().getClassLoader());
            Method canSee = apiClass.getMethod("canSee", Player.class, Player.class);
            Object result = canSee.invoke(null, viewer, target);
            if (result instanceof Boolean visible) {
                return visible;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static Boolean canSeeViaEssentials(Player viewer, Player target) {
        Plugin plugin = viewer.getServer().getPluginManager().getPlugin("Essentials");
        if (plugin == null || !plugin.isEnabled()) {
            return null;
        }

        try {
            Method getUser = plugin.getClass().getMethod("getUser", Player.class);
            Object user = getUser.invoke(plugin, target);
            if (user == null) {
                return null;
            }

            try {
                Method isHiddenForViewer = user.getClass().getMethod("isHidden", Player.class);
                Object result = isHiddenForViewer.invoke(user, viewer);
                if (result instanceof Boolean hidden) {
                    return !hidden;
                }
            } catch (NoSuchMethodException ignored) {
            }

            Method isVanished = user.getClass().getMethod("isVanished");
            Object vanished = isVanished.invoke(user);
            if (vanished instanceof Boolean hidden) {
                return !hidden;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static boolean hasVanishedMetadata(Player target) {
        for (MetadataValue metadataValue : target.getMetadata("vanished")) {
            if (metadataValue.asBoolean()) {
                return true;
            }
        }
        return false;
    }
}
