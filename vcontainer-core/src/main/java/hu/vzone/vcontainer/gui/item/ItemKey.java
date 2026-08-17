package hu.vzone.vcontainer.gui.item;

import hu.vzone.vcontainer.utils.ItemUtils;
import org.bukkit.inventory.ItemStack;

/** Uses the same stack identity as storage insertion, so the GUI cannot split equal custom items. */
final class ItemKey {
    private final ItemStack item;
    private final int hashCode;

    private ItemKey(ItemStack item) {
        this.item = item;
        this.hashCode = 31 * item.getType().hashCode() + (item.hasItemMeta() ? item.getItemMeta().hashCode() : 0);
    }

    static ItemKey of(ItemStack item) {
        ItemStack fingerprint = item.clone();
        fingerprint.setAmount(1);
        return new ItemKey(fingerprint);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ItemKey key && ItemUtils.isSameItemWithNBT(item, key.item);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
