package hu.vzone.vcontainer.utils;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class ItemUtils {
    public static boolean isSimilarStack(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getType() != b.getType()) return false;
        if (a.hasItemMeta() != b.hasItemMeta()) return false;
        if (a.hasItemMeta()) {
            if (!a.getItemMeta().equals(b.getItemMeta())) return false;
        }
        return true;
    }

    public static boolean isSameItemWithNBT(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.getType() != b.getType()) return false;
        boolean aHasMeta = a.hasItemMeta();
        boolean bHasMeta = b.hasItemMeta();
        if (aHasMeta != bHasMeta) return false;
        if (!aHasMeta) return true;

        ItemMeta ma = a.getItemMeta();
        ItemMeta mb = b.getItemMeta();
        if (ma == null && mb == null) return true;
        if (ma == null || mb == null) return false;
        return ma.equals(mb);
    }

    public static boolean isSameItemWithNBT(ItemStack current, ItemStack target, boolean targetHasMeta, ItemMeta targetMeta) {
        if (current == null || target == null) return false;
        if (current.getType() != target.getType()) return false;

        boolean currentHasMeta = current.hasItemMeta();
        if (currentHasMeta != targetHasMeta) return false;
        if (!currentHasMeta) return true;

        ItemMeta currentMeta = current.getItemMeta();
        if (currentMeta == null && targetMeta == null) return true;
        if (currentMeta == null || targetMeta == null) return false;
        return currentMeta.equals(targetMeta);
    }

    public static String itemsToBase64(List<ItemStack> items) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeInt(items.size());
            for (ItemStack item : items) {
                boos.writeObject(item);
            }
            boos.flush();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

    public static List<ItemStack> itemsFromBase64(String data) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(data);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            int size = bois.readInt();
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                ItemStack item = (ItemStack) bois.readObject();
                items.add(item);
            }
            return items;
        }
    }

    public static byte[] toBytes(ItemStack item) {
        return item.serializeAsBytes();
    }

    public static ItemStack fromBytes(byte[] bytes) {
        return ItemStack.deserializeBytes(bytes);
    }
}
