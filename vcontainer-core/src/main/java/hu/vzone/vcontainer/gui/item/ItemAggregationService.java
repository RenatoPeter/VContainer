package hu.vzone.vcontainer.gui.item;

import hu.vzone.vcontainer.utils.ItemDisplayNames;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds one immutable visual entry for every metadata-identical stored item type. */
public final class ItemAggregationService {
    private ItemAggregationService() {
    }

    public static List<AggregatedItem> aggregate(List<ItemStack> source) {
        Map<ItemKey, MutableItem> grouped = new LinkedHashMap<>();
        for (ItemStack item : source) {
            if (item == null || item.getType().isAir() || item.getAmount() <= 0) continue;
            ItemKey key = ItemKey.of(item);
            MutableItem entry = grouped.get(key);
            if (entry == null) {
                ItemStack template = item.clone();
                template.setAmount(1);
                grouped.put(key, new MutableItem(template, item.getAmount()));
            } else {
                entry.amount = saturatedAdd(entry.amount, item.getAmount());
            }
        }

        List<AggregatedItem> result = new ArrayList<>(grouped.size());
        for (MutableItem entry : grouped.values()) {
            String name = ItemDisplayNames.resolve(entry.template);
            result.add(new AggregatedItem(entry.template, entry.amount, normalize(name + " " + entry.template.getType().name())));
        }
        return result;
    }

    public static List<AggregatedItem> filter(List<AggregatedItem> source, String query) {
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) return new ArrayList<>(source);

        List<AggregatedItem> result = new ArrayList<>();
        for (AggregatedItem entry : source) {
            if (entry.searchName().contains(normalizedQuery)) result.add(entry);
        }
        return result;
    }

    public static String normalize(String input) {
        if (input == null || input.isBlank()) return "";
        String withoutLegacy = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', input));
        if (withoutLegacy != null) withoutLegacy = withoutLegacy.replaceAll("<[^>]+>", "");
        return withoutLegacy == null ? "" : withoutLegacy.toLowerCase(Locale.ROOT);
    }

    private static int saturatedAdd(int first, int second) {
        long value = (long) first + second;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static final class MutableItem {
        private final ItemStack template;
        private int amount;

        private MutableItem(ItemStack template, int amount) {
            this.template = template;
            this.amount = amount;
        }
    }
}
