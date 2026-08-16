package hu.vzone.vcontainer.gui.item;

import org.bukkit.inventory.ItemStack;

import java.util.Arrays;

/** Full item fingerprint used only while grouping a container view. */
final class ItemKey {
    private final byte[] data;
    private final int hashCode;

    private ItemKey(byte[] data) {
        this.data = data;
        this.hashCode = Arrays.hashCode(data);
    }

    static ItemKey of(ItemStack item) {
        ItemStack fingerprint = item.clone();
        fingerprint.setAmount(1);
        return new ItemKey(fingerprint.serializeAsBytes());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ItemKey key && Arrays.equals(data, key.data);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
