package hu.vzone.vContainer.listeners;

import hu.vzone.vContainer.managers.ContainerManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class TesztL implements Listener {
    private final ContainerManager manager;


    public TesztL(ContainerManager manager) { this.manager = manager; }

    @EventHandler
    public void onSomething(PlayerMoveEvent event){
        Player player = event.getPlayer();

        manager.addItemToContainer(player, getRandomItemStack());
    }

    public static int getRandomNumber() {
//        return (int) (Math.random() * 64) + 1;
        return 5;
    }

    public static ItemStack getRandomItemStack() {
        int ize = (int) (Math.random() * 5) + 1;
        if(ize == 1) {
            ItemStack itemStack = new ItemStack(Material.APPLE);
            ItemMeta itemMeta = itemStack.getItemMeta();
            itemMeta.setDisplayName("§3Hal");
            itemMeta.setUnbreakable(true);
            itemMeta.setEnchantmentGlintOverride(true);
            itemStack.setItemMeta(itemMeta);
            itemStack.setAmount(getRandomNumber());
            return itemStack;
        }
        if(ize == 2) {
            ItemStack itemStack = new ItemStack(Material.SUNFLOWER);
            ItemMeta itemMeta = itemStack.getItemMeta();
            itemMeta.setDisplayName("§aKeksz");
            itemMeta.setUnbreakable(true);
            itemMeta.setEnchantmentGlintOverride(true);
            itemStack.setItemMeta(itemMeta);
            itemStack.setAmount(getRandomNumber());
            return itemStack;
        }
        if(ize == 3) {
            ItemStack itemStack = new ItemStack(Material.PUFFERFISH);
            ItemMeta itemMeta = itemStack.getItemMeta();
            itemMeta.setDisplayName("§dAlma");
            itemMeta.setUnbreakable(true);
            itemMeta.setEnchantmentGlintOverride(true);
            itemStack.setItemMeta(itemMeta);
            itemStack.setAmount(getRandomNumber());
            return itemStack;
        }
        if(ize == 4) {
            ItemStack itemStack = new ItemStack(Material.END_ROD);
            ItemMeta itemMeta = itemStack.getItemMeta();
            itemMeta.setDisplayName("§eFASZ");
            itemMeta.setUnbreakable(true);
            itemMeta.setEnchantmentGlintOverride(true);
            itemStack.setItemMeta(itemMeta);
            itemStack.setAmount(getRandomNumber());
            return itemStack;
        }
        if(ize == 5) {
            ItemStack itemStack = new ItemStack(Material.RAW_COPPER);
            ItemMeta itemMeta = itemStack.getItemMeta();
            itemMeta.setDisplayName("§6ARANY");
            itemMeta.setUnbreakable(true);
            itemMeta.setEnchantmentGlintOverride(true);
            itemStack.setItemMeta(itemMeta);
            itemStack.setAmount(getRandomNumber());
            return itemStack;
        }
        return new ItemStack(Material.STONE);
    }
}
