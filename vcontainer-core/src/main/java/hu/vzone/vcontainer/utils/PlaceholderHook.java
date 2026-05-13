package hu.vzone.vcontainer.utils;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

public final class PlaceholderHook {

    private static Boolean available;
    private static Method setPlaceholdersMethod;

    private PlaceholderHook() {
    }

    public static String apply(OfflinePlayer player, String text) {
        if (text == null || text.isBlank() || !isAvailable()) return text;
        try {
            Object result = setPlaceholdersMethod.invoke(null, player, text);
            return result == null ? text : String.valueOf(result);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return text;
        }
    }

    private static boolean isAvailable() {
        if (available != null) return available;
        try {
            if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
                available = false;
                return false;
            }
        } catch (RuntimeException e) {
            available = false;
            return false;
        }
        try {
            Class<?> apiClass = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            setPlaceholdersMethod = apiClass.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            available = true;
        } catch (ReflectiveOperationException e) {
            available = false;
        }
        return available;
    }
}
