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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class AuditLogger {
    private static final Queue<String> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger QUEUED_LINES = new AtomicInteger();
    private static final AtomicLong DROPPED_LINES = new AtomicLong();
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

        int maxQueuedLines = Math.max(1, plugin.getConfig().getInt("audit.max-queued-lines", 8192));
        while (true) {
            int queued = QUEUED_LINES.get();
            if (queued >= maxQueuedLines) {
                DROPPED_LINES.incrementAndGet();
                return;
            }
            if (QUEUED_LINES.compareAndSet(queued, queued + 1)) break;
        }
        QUEUE.add(line);
        ensureTask(plugin);
    }

    public static void logSystem(String action, String target, String detail) {
        log(action, null, target, detail);
    }

    public static void shutdown() {
        shutdown(true);
    }

    /** Runtime plugin unloaders must not turn queued audit file I/O into a server-thread stall. */
    public static void shutdown(boolean flush) {
        VContainer plugin = VContainer.getInstance();
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        if (flush && plugin != null) {
            flushAll(plugin);
        }
        QUEUE.clear();
        QUEUED_LINES.set(0);
        DROPPED_LINES.set(0);
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
            QUEUED_LINES.decrementAndGet();
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
            long dropped = DROPPED_LINES.getAndSet(0L);
            if (dropped > 0L) {
                plugin.getLogger().warning("Audit queue reached its limit; dropped " + dropped + " audit line(s).");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write audit log: " + e.getMessage());
        }
    }

    private static void flushAll(VContainer plugin) {
        while (!QUEUE.isEmpty()) {
            int before = QUEUE.size();
            flush(plugin);
            if (QUEUE.size() == before) {
                break;
            }
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
