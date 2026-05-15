package net.brcdev.myeconomy.provider;

import net.brcdev.shopgui.provider.economy.EconomyProvider;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MyEconomyProvider extends EconomyProvider {

  private Map<UUID, Double> playerBalances;

  public MyEconomyProvider() {
    // (Optional, default ones are empty strings)
    // Override fields from superclass to set currency prefix & suffix
    this.currencyPrefix = "$";
    this.currencySuffix = "";

    this.playerBalances = new HashMap<>();
  }

  @Override
  public String getName() {
    return "MyEconomy";
  }

  @Override
  public double getBalance(Player player) {
    return getPlayerBalance(player);
  }

  @Override
  public void deposit(Player player, double v) {
    double balance = getPlayerBalance(player);
    playerBalances.put(player.getUniqueId(), balance + v);
  }

  @Override
  public void withdraw(Player player, double v) {
    double balance = getPlayerBalance(player);
    playerBalances.put(player.getUniqueId(), balance - v);
  }

  private Double getPlayerBalance(Player player) {
    return playerBalances.getOrDefault(player.getUniqueId(), 0.0);
  }
}
