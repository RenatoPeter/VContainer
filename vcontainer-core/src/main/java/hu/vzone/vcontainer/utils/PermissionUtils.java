package hu.vzone.vcontainer.utils;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class PermissionUtils {

    private PermissionUtils() {
    }

    public static boolean has(CommandSender sender, String permission) {
        if (sender instanceof Player player && player.isOp()) return true;
        return sender.hasPermission(permission);
    }
}
