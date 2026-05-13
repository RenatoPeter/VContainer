package hu.vzone.vcontainer.utils;

import hu.vzone.vcontainer.VContainer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class AuditLogger {
    private static final Queue<String> QUEUE = new ConcurrentLinkedQueue<>();
    private static BukkitTask flushTask;

    private AuditLogger() {
    }

    public static void log(String action, CommandSender actor, String target, String detail) {
        VContainer plugin = VContainer.getInstance();
        if (plugin == null || !plugin.getConfig().getBoolean("audit.enabled", true)) return;
        if (action != null && action.startsWith("hopper-") && !plugin.getConfig().getBoolean("audit.hopper", false)) return;

        String actorName = actor == null ? "SYSTEM" : actor.getName();
        String actorUuid = actor instanceof Player player ? player.getUniqueId().toString() : "-";
        String line = Instant.now()
                + " action=" + sanitize(action)
                + " actor=" + sanitize(actorName)
                + " actorUuid=" + actorUuid
                + " target=" + sanitize(target)
                + " detail=" + sanitize(detail)
                + System.lineSeparator();

        QUEUE.add(line);
        ensureTask(plugin);
    }

    public static void logSystem(String action, String target, String detail) {
        log(action, null, target, detail);
    }

    public static void shutdown() {
        VContainer plugin = VContainer.getInstance();
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (plugin != null) flush(plugin);
    }

    private static void ensureTask(VContainer plugin) {
        if (flushTask != null) return;
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> flush(plugin), 20L * 5L, 20L * 5L);
    }

    private static void flush(VContainer plugin) {
        if (QUEUE.isEmpty()) return;
        List<String> lines = new ArrayList<>();
        int max = Math.max(1, plugin.getConfig().getInt("audit.batch-size", 256));
        for (int i = 0; i < max; i++) {
            String line = QUEUE.poll();
            if (line == null) break;
            lines.add(line);
        }
        if (lines.isEmpty()) return;

        File file = new File(plugin.getDataFolder(), plugin.getConfig().getString("audit.file", "audit.log"));
        try {
            file.getParentFile().mkdirs();
            rotateIfNeeded(plugin, file);
            Files.writeString(file.toPath(), String.join("", lines), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write audit log: " + e.getMessage());
        }
    }

    private static void rotateIfNeeded(VContainer plugin, File file) throws IOException {
        long maxBytes = plugin.getConfig().getLong("audit.max-file-size-bytes", 10L * 1024L * 1024L);
        if (maxBytes <= 0 || !file.exists() || file.length() < maxBytes) return;

        File rotated = new File(file.getParentFile(), file.getName() + "." + Instant.now().toString().replace(':', '-') + ".old");
        Files.move(file.toPath(), rotated.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "-";
        return value.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
    }
}
