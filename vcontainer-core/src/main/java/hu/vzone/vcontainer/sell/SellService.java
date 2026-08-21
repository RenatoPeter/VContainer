package hu.vzone.vcontainer.sell;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.managers.ContainerManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class SellService {

    private final VContainer plugin;
    private final Map<Material, Double> ownPriceCache = new ConcurrentHashMap<>();
    private volatile boolean loggedVaultHookError;
    private volatile boolean loggedShopGuiPlusHookError;
    private volatile boolean loggedEconomyShopGuiHookError;
    private volatile boolean loggedEconomyShopGuiLimitUpdateError;

    public SellService(VContainer plugin) {
        this.plugin = plugin;
    }

    public boolean isSellEnabled() {
        return getSellProvider() != SellProviderType.NONE;
    }

    public boolean isVaultCurrencySelected() {
        return getCurrencyType() == CurrencyType.VAULT;
    }

    public boolean canSell(Player player, ItemStack item, int amount) {
        return quote(player, item, amount).sellable();
    }

    /** Clears configuration-derived price data after a plugin or prices.yml reload. */
    public void reload() {
        ownPriceCache.clear();
    }

    public void shutdown() {
        ownPriceCache.clear();
    }

    public SellQuote quote(Player player, ItemStack item, int amount) {
        if (player == null || item == null || item.getType().isAir() || amount <= 0) {
            return SellQuote.unsellable(UnavailableReason.NO_PRICE, "");
        }
        if (!isSellEnabled()) {
            return SellQuote.unsellable(UnavailableReason.DISABLED, "");
        }
        if (!isVaultCurrencySelected()) {
            return SellQuote.unsellable(UnavailableReason.CURRENCY_UNAVAILABLE, "");
        }
        if (!hasVaultEconomy()) {
            return SellQuote.unsellable(UnavailableReason.CURRENCY_UNAVAILABLE, "");
        }

        ItemStack pricedItem = item.clone();
        pricedItem.setAmount(amount);

        return switch (getSellProvider()) {
            case NONE -> SellQuote.unsellable(UnavailableReason.DISABLED, "");
            case OWN -> quoteOwn(pricedItem);
            case ECONOMY_SHOP_GUI -> quoteEconomyShopGui(player, pricedItem);
            case SHOP_GUI_PLUS -> quoteShopGuiPlus(player, pricedItem);
        };
    }

    public SellResult sell(Player player, ContainerManager manager, java.util.UUID ownerId, ItemStack item, int amount) {
        SellQuote quote = quote(player, item, amount);
        if (!quote.sellable()) {
            return SellResult.failed(quote.reason());
        }

        ItemStack target = item.clone();
        target.setAmount(1);
        int removed = manager.takeExactItemFromContainer(ownerId, target, amount);
        if (removed != amount) {
            return SellResult.failed(UnavailableReason.NO_PRICE);
        }

        if (!deposit(player, quote.totalPrice())) {
            manager.restoreItemToContainer(ownerId, cloneWithAmount(item, removed));
            return SellResult.failed(UnavailableReason.CURRENCY_UNAVAILABLE);
        }

        quote.complete();
        return SellResult.success(removed, quote.totalPrice(), quote.formattedPrice());
    }

    public String formatPrice(double amount) {
        String formatted = vaultFormat(amount);
        if (formatted != null && !formatted.isBlank()) {
            return formatted;
        }
        return String.format(Locale.US, "%.2f", amount);
    }

    public String unavailablePricePlaceholder() {
        return VContainer.formatMessage(plugin.getConfig().getString(
                "container-options.sell.unavailable-price-placeholder",
                "&cN/A"
        ));
    }

    private SellQuote quoteOwn(ItemStack item) {
        double unitPrice = ownPriceCache.computeIfAbsent(item.getType(), this::loadOwnUnitPrice);
        if (unitPrice < 0.0D) {
            return SellQuote.unsellable(UnavailableReason.NO_PRICE, "");
        }

        double total = unitPrice * item.getAmount();
        return SellQuote.sellable(total, formatPrice(total), () -> {});
    }

    private double loadOwnUnitPrice(Material material) {
        ConfigurationSection prices = plugin.getPricesConfig().getConfigurationSection("prices");
        if (prices == null || !prices.contains(material.name())) {
            return -1.0D;
        }
        return prices.getDouble(material.name(), -1.0D);
    }

    private SellQuote quoteShopGuiPlus(Player player, ItemStack item) {
        Plugin hooked = Bukkit.getPluginManager().getPlugin("ShopGUIPlus");
        if (hooked == null || !hooked.isEnabled()) {
            return SellQuote.unsellable(UnavailableReason.PROVIDER_UNAVAILABLE, "");
        }

        try {
            Class<?> apiClass = Class.forName("net.brcdev.shopgui.ShopGuiPlusApi");
            Method getPluginMethod = apiClass.getMethod("getPlugin");
            Object shopPlugin = getPluginMethod.invoke(null);
            if (shopPlugin == null) {
                return SellQuote.unsellable(UnavailableReason.PROVIDER_UNAVAILABLE, "");
            }

            Object shopManager = shopPlugin.getClass().getMethod("getShopManager").invoke(shopPlugin);
            boolean loaded = (boolean) shopManager.getClass().getMethod("areShopsLoaded").invoke(shopManager);
            if (!loaded) {
                return SellQuote.unsellable(UnavailableReason.PROVIDER_UNAVAILABLE, "");
            }

            Method priceMethod = apiClass.getMethod("getItemStackPriceSell", Player.class, ItemStack.class);
            Object rawPrice = priceMethod.invoke(null, player, item);
            double total = rawPrice instanceof Number number ? number.doubleValue() : -1.0D;
            if (total < 0.0D) {
                return SellQuote.unsellable(UnavailableReason.NO_PRICE, "");
            }

            return SellQuote.sellable(total, formatPrice(total), () -> {});
        } catch (ReflectiveOperationException ex) {
            logWarningOnce("Could not hook into ShopGUIPlus sell API.", ex, HookType.SHOP_GUI_PLUS);
            return SellQuote.unsellable(UnavailableReason.PROVIDER_UNAVAILABLE, "");
        }
    }

    private SellQuote quoteEconomyShopGui(Player player, ItemStack item) {
        Plugin hooked = Bukkit.getPluginManager().getPlugin("EconomyShopGUI");
        if (hooked == null) {
            hooked = Bukkit.getPluginManager().getPlugin("EconomyShopGUI-Premium");
        }
        if (hooked == null || !hooked.isEnabled()) {
            return SellQuote.unsellable(UnavailableReason.PROVIDER_UNAVAILABLE, "");
        }

        try {
            Class<?> hookClass = Class.forName("me.gypopo.economyshopgui.api.EconomyShopGUIHook");
            Method getSellPrice = hookClass.getMethod("getSellPrice", org.bukkit.OfflinePlayer.class, ItemStack.class);
            Object optional = getSellPrice.invoke(null, player, item);
            if (!(optional instanceof Optional<?> resolved) || resolved.isEmpty()) {
                return SellQuote.unsellable(UnavailableReason.NO_PRICE, "");
            }

            Object sellPrice = resolved.get();
            Class<?> ecoTypeClass = Class.forName("me.gypopo.economyshopgui.util.EcoType");
            Object vaultEcoType = ecoTypeClass.getMethod("getFromString", String.class).invoke(null, "VAULT");
            Object rawPrice = sellPrice.getClass().getMethod("getPrice", ecoTypeClass).invoke(sellPrice, vaultEcoType);
            double total = rawPrice instanceof Number number ? number.doubleValue() : -1.0D;
            if (total < 0.0D) {
                return SellQuote.unsellable(UnavailableReason.NO_PRICE, "");
            }

            Object finalSellPrice = sellPrice;
            return SellQuote.sellable(total, formatPrice(total), () -> {
                try {
                    finalSellPrice.getClass().getMethod("updateLimits").invoke(finalSellPrice);
                } catch (ReflectiveOperationException ex) {
                    logWarningOnce("Could not update EconomyShopGUI sell limits after sale.", ex, HookType.ECONOMY_SHOP_GUI_LIMITS);
                }
            });
        } catch (ReflectiveOperationException ex) {
            logWarningOnce("Could not hook into EconomyShopGUI sell API.", ex, HookType.ECONOMY_SHOP_GUI);
            return SellQuote.unsellable(UnavailableReason.PROVIDER_UNAVAILABLE, "");
        }
    }

    private boolean deposit(Player player, double amount) {
        if (amount <= 0.0D) {
            return true;
        }

        Object economy = getVaultEconomy();
        if (economy == null) {
            return false;
        }

        try {
            try {
                Object response = economy.getClass().getMethod("depositPlayer", org.bukkit.OfflinePlayer.class, double.class)
                        .invoke(economy, player, amount);
                return transactionSucceeded(response);
            } catch (NoSuchMethodException ignored) {
                Object response = economy.getClass().getMethod("depositPlayer", String.class, double.class)
                        .invoke(economy, player.getName(), amount);
                return transactionSucceeded(response);
            }
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not deposit sold money with Vault.", ex);
            return false;
        }
    }

    private boolean transactionSucceeded(Object response) {
        if (response == null) {
            return false;
        }
        try {
            Object success = response.getClass().getMethod("transactionSuccess").invoke(response);
            return success instanceof Boolean bool && bool;
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not read Vault transaction response.", ex);
            return false;
        }
    }

    private boolean hasVaultEconomy() {
        return getVaultEconomy() != null;
    }

    private String vaultFormat(double amount) {
        Object economy = getVaultEconomy();
        if (economy == null) {
            return null;
        }

        try {
            Object formatted = economy.getClass().getMethod("format", double.class).invoke(economy, amount);
            return formatted == null ? null : String.valueOf(formatted);
        } catch (ReflectiveOperationException ex) {
            plugin.getLogger().log(Level.FINE, "Could not format Vault price.", ex);
            return null;
        }
    }

    private Object getVaultEconomy() {
        Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null || !vault.isEnabled()) {
            return null;
        }

        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Object servicesManager = Bukkit.getServicesManager();
            Object registration = servicesManager.getClass()
                    .getMethod("getRegistration", Class.class)
                    .invoke(servicesManager, economyClass);
            if (registration == null) {
                return null;
            }
            return registration.getClass().getMethod("getProvider").invoke(registration);
        } catch (ReflectiveOperationException ex) {
            logWarningOnce("Could not access Vault economy provider.", ex, HookType.VAULT);
            return null;
        }
    }

    private void logWarningOnce(String message, Throwable throwable, HookType type) {
        if (type == HookType.VAULT) {
            if (loggedVaultHookError) return;
            loggedVaultHookError = true;
        } else if (type == HookType.SHOP_GUI_PLUS) {
            if (loggedShopGuiPlusHookError) return;
            loggedShopGuiPlusHookError = true;
        } else if (type == HookType.ECONOMY_SHOP_GUI) {
            if (loggedEconomyShopGuiHookError) return;
            loggedEconomyShopGuiHookError = true;
        } else {
            if (loggedEconomyShopGuiLimitUpdateError) return;
            loggedEconomyShopGuiLimitUpdateError = true;
        }
        plugin.getLogger().log(Level.WARNING, message, throwable);
    }

    private SellProviderType getSellProvider() {
        String raw = plugin.getConfig().getString("container-options.sell.provider", "NONE");
        return SellProviderType.fromConfig(raw);
    }

    private CurrencyType getCurrencyType() {
        String raw = plugin.getConfig().getString("container-options.sell.currency-provider", "VAULT");
        return CurrencyType.fromConfig(raw);
    }

    private ItemStack cloneWithAmount(ItemStack item, int amount) {
        if (item == null || amount <= 0) {
            return new ItemStack(Material.AIR);
        }
        ItemStack clone = item.clone();
        clone.setAmount(amount);
        return clone;
    }

    public enum UnavailableReason {
        DISABLED,
        PROVIDER_UNAVAILABLE,
        CURRENCY_UNAVAILABLE,
        NO_PRICE
    }

    private enum HookType {
        VAULT,
        SHOP_GUI_PLUS,
        ECONOMY_SHOP_GUI,
        ECONOMY_SHOP_GUI_LIMITS
    }

    private enum SellProviderType {
        NONE,
        OWN,
        ECONOMY_SHOP_GUI,
        SHOP_GUI_PLUS;

        private static SellProviderType fromConfig(String raw) {
            if (raw == null) {
                return NONE;
            }
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "OWN" -> OWN;
                case "ECONOMYSHOPGUI", "ECONOMY_SHOP_GUI" -> ECONOMY_SHOP_GUI;
                case "SHOPGUIPLUS", "SHOP_GUI_PLUS" -> SHOP_GUI_PLUS;
                default -> NONE;
            };
        }
    }

    private enum CurrencyType {
        VAULT,
        NONE;

        private static CurrencyType fromConfig(String raw) {
            if (raw == null) {
                return VAULT;
            }
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "VAULT" -> VAULT;
                default -> NONE;
            };
        }
    }

    public record SellQuote(boolean sellable, double totalPrice, String formattedPrice, UnavailableReason reason, Runnable onComplete) {

        public static SellQuote sellable(double totalPrice, String formattedPrice, Runnable onComplete) {
            return new SellQuote(true, totalPrice, formattedPrice, null, onComplete);
        }

        public static SellQuote unsellable(UnavailableReason reason, String formattedPrice) {
            return new SellQuote(false, 0.0D, formattedPrice, reason, () -> {});
        }

        public void complete() {
            onComplete.run();
        }
    }

    public record SellResult(boolean success, int amount, double totalPrice, String formattedPrice, UnavailableReason reason) {

        public static SellResult success(int amount, double totalPrice, String formattedPrice) {
            return new SellResult(true, amount, totalPrice, formattedPrice, null);
        }

        public static SellResult failed(UnavailableReason reason) {
            return new SellResult(false, 0, 0.0D, "", reason);
        }
    }

}
