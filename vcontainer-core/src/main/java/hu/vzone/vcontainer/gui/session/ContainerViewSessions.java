package hu.vzone.vcontainer.gui.session;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-viewer transient UI state. It is deliberately never persisted. */
public final class ContainerViewSessions {
    private static final Map<UUID, String> SEARCHES = new ConcurrentHashMap<>();

    private ContainerViewSessions() {
    }

    public static String search(UUID viewerId) {
        return SEARCHES.getOrDefault(viewerId, "");
    }

    public static void setSearch(UUID viewerId, String search) {
        if (search == null || search.isBlank()) SEARCHES.remove(viewerId);
        else SEARCHES.put(viewerId, search);
    }

    public static void clear(UUID viewerId) {
        SEARCHES.remove(viewerId);
    }

    public static void clearAll() {
        SEARCHES.clear();
    }
}
