package hu.vzone.vcontainer.utils;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerViewingCache {
    private static final Map<Player, PageInfo> map = new ConcurrentHashMap<>();


    public static void setViewing(Player p, int current, int max) { map.put(p, new PageInfo(current, max)); }
    public static PageInfo getViewing(Player p) { return map.get(p); }
    public static void remove(Player p) { map.remove(p); }


    public static class PageInfo {
        public final int current; public final int max;
        public PageInfo(int c, int m) { this.current = c; this.max = m; }
    }
}
