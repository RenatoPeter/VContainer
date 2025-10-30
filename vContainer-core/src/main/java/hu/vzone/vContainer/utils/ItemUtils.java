package hu.vzone.vContainer.utils;

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

        ItemMeta ma = a.getItemMeta();
        ItemMeta mb = b.getItemMeta();
        if (ma == null && mb == null) return true;
        if (ma == null || mb == null) return false;

        // ItemMeta equals() figyelembe veszi az NBT-t is (display name, lore, enchant, PDC stb.)
        return ma.equals(mb);
    }

    public static String itemsToBase64(List<ItemStack> items) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BukkitObjectOutputStream boos = new BukkitObjectOutputStream(baos);
        boos.writeInt(items.size());
        for (ItemStack item : items) {
            boos.writeObject(item);
        }
        boos.close();
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    public static List<ItemStack> itemsFromBase64(String data) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(data);
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        BukkitObjectInputStream bois = new BukkitObjectInputStream(bais);
        int size = bois.readInt();
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack item = (ItemStack) bois.readObject();
            items.add(item);
        }
        bois.close();
        return items;
    }
}
