package hu.vzone.vcontainer.utils;

import hu.vzone.vcontainer.VContainer;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Lightweight, opt-in persistence diagnostics kept separate from the normal audit log. */
public final class ContainerDebugLogger {
    private static final Queue<String> QUEUE = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger QUEUED_LINES = new AtomicInteger();
    private static final AtomicLong DROPPED_LINES = new AtomicLong();
    private static final ConcurrentHashMap<UUID, LongAdder> ACTION_COUNTS = new ConcurrentHashMap<>();
    private static BukkitTask flushTask;

    private ContainerDebugLogger() {
    }

    public static void containerLoaded(UUID ownerId, Collection<ItemStack> items) {
        if (!enabled() || ownerId == null) return;
        enqueue("LOAD owner=" + ownerId + " " + itemSummary(items));
    }

    public static void containerAction(UUID ownerId) {
        if (!enabled() || ownerId == null) return;
        ACTION_COUNTS.computeIfAbsent(ownerId, ignored -> new LongAdder()).increment();
    }

    public static void containerSaved(UUID ownerId, Collection<ItemStack> items, SaveCause cause) {
        if (!enabled() || ownerId == null) return;
        long actions = ACTION_COUNTS.getOrDefault(ownerId, new LongAdder()).sum();
        enqueue("SAVE cause=" + cause + " owner=" + ownerId + " " + itemSummary(items) + " actions=" + actions);
    }

    public static void containerSaveFailed(UUID ownerId, SaveCause cause) {
        if (!enabled() || ownerId == null) return;
        long actions = ACTION_COUNTS.getOrDefault(ownerId, new LongAdder()).sum();
        enqueue("SAVE_FAILED cause=" + cause + " owner=" + ownerId + " actions=" + actions);
    }

    /** Removes per-player diagnostics as soon as its active container cache is evicted. */
    public static void clearPlayer(UUID ownerId) {
        if (ownerId != null) ACTION_COUNTS.remove(ownerId);
    }

    public static void shutdown(boolean flush) {
        VContainer plugin = VContainer.getInstance();
        BukkitTask task = flushTask;
        if (task != null) {
            task.cancel();
            flushTask = null;
        }
        if (flush && plugin != null) {
            flushAll(plugin);
        }
        QUEUE.clear();
        QUEUED_LINES.set(0);
        DROPPED_LINES.set(0L);
        ACTION_COUNTS.clear();
    }

    private static boolean enabled() {
        VContainer plugin = VContainer.getInstance();
        return plugin != null && plugin.getConfig().getBoolean("debug.enabled", false);
    }

    private static void enqueue(String message) {
        VContainer plugin = VContainer.getInstance();
        if (plugin == null) return;

        int maxQueuedLines = Math.max(1, plugin.getConfig().getInt("debug.max-queued-lines", 8192));
        while (true) {
            int queued = QUEUED_LINES.get();
            if (queued >= maxQueuedLines) {
                DROPPED_LINES.incrementAndGet();
                return;
            }
            if (QUEUED_LINES.compareAndSet(queued, queued + 1)) break;
        }

        QUEUE.add(Instant.now() + " " + message + System.lineSeparator());
        ensureTask(plugin);
    }

    private static synchronized void ensureTask(VContainer plugin) {
        if (flushTask != null && !flushTask.isCancelled()) return;
        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> flush(plugin), 20L * 5L, 20L * 5L);
    }

    private static void flush(VContainer plugin) {
        if (QUEUE.isEmpty()) return;

        List<String> lines = new ArrayList<>();
        int batchSize = Math.max(1, plugin.getConfig().getInt("debug.batch-size", 256));
        for (int index = 0; index < batchSize; index++) {
            String line = QUEUE.poll();
            if (line == null) break;
            QUEUED_LINES.decrementAndGet();
            lines.add(line);
        }
        if (lines.isEmpty()) return;

        File file = new File(plugin.getDataFolder(), plugin.getConfig().getString("debug.file", "container-debug.log"));
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.writeString(file.toPath(), String.join("", lines), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            long dropped = DROPPED_LINES.getAndSet(0L);
            if (dropped > 0L) {
                plugin.getLogger().warning("Container debug queue reached its limit; dropped " + dropped + " line(s).");
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Failed to write container debug log: " + exception.getMessage());
        }
    }

    private static void flushAll(VContainer plugin) {
        while (!QUEUE.isEmpty()) {
            int before = QUEUE.size();
            flush(plugin);
            if (QUEUE.size() == before) break;
        }
    }

    private static String itemSummary(Collection<ItemStack> items) {
        int stacks = 0;
        long amount = 0L;
        if (items != null) {
            for (ItemStack item : items) {
                if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;
                stacks++;
                amount += item.getAmount();
            }
        }
        return "stacks=" + stacks + " amount=" + amount;
    }

    public enum SaveCause {
        AUTO_SAVE,
        SHUTDOWN,
        MANUAL
    }
}
