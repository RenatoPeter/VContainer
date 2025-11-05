package hu.vzone.vcontainer.utils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerViewingCache {

    // Player helyett UUID — így nem tartunk erős referenciát Player objektumokra
    private static final Map<UUID, PageInfo> map = new ConcurrentHashMap<>();

    public static void setViewing(UUID playerId, int current, int max) {
        map.put(playerId, new PageInfo(current, max));
    }

    public static PageInfo getViewing(UUID playerId) {
        return map.get(playerId);
    }

    public static void remove(UUID playerId) {
        map.remove(playerId);
    }

    // Plugin leálláskor vagy reloadkor hívható
    public static void clearAll() {
        map.clear();
    }

    public static class PageInfo {
        public final int current;
        public final int max;
        public PageInfo(int c, int m) {
            this.current = c;
            this.max = m;
        }
    }
}
