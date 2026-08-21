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
    private static final int STORAGE_FORMAT_VERSION = -2;
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

    /**
     * Compares the complete serialized item data while deliberately ignoring its amount.
     * GUI withdrawals use this stricter check so two custom items with visually equal meta
     * can never be substituted for one another.
     */
    public static boolean isSameStoredItem(ItemStack current, ItemStack target, boolean targetHasMeta, ItemMeta targetMeta) {
        if (!isSameItemWithNBT(current, target, targetHasMeta, targetMeta)) return false;

        ItemStack currentFingerprint = current.clone();
        ItemStack targetFingerprint = target.clone();
        currentFingerprint.setAmount(1);
        targetFingerprint.setAmount(1);
        return java.util.Arrays.equals(currentFingerprint.serializeAsBytes(), targetFingerprint.serializeAsBytes());
    }

    public static String itemsToBase64(List<ItemStack> items) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos)) {
            boos.writeInt(STORAGE_FORMAT_VERSION);
            boos.writeInt(items.size());
            for (ItemStack item : items) {
                ItemStack data = item.clone();
                int amount = data.getAmount();
                data.setAmount(1);
                boos.writeObject(data);
                boos.writeInt(amount);
            }
            boos.flush();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

    public static List<ItemStack> itemsFromBase64(String data) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(data);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream bois = new BukkitObjectInputStream(bais)) {
            int header = bois.readInt();
            boolean versioned = header == STORAGE_FORMAT_VERSION;
            int size = versioned ? bois.readInt() : header;
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                ItemStack item = (ItemStack) bois.readObject();
                if (versioned) {
                    int amount = bois.readInt();
                    if (amount > 0) item.setAmount(amount);
                }
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
