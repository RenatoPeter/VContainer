package hu.vzone.vcontainer.managers;

import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.utils.ItemUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Hopper;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class StorageBlockManager {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final String HOLOGRAM_TAG = "vcontainer_storage_hologram";
    private static final BlockFace[] HOPPER_INPUT_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP
    };

    private final VContainer plugin;
    private final ContainerManager containerManager;
    private final File legacyFile;
    private final File globalFile;
    private final File personalFile;
    private final Map<String, UUID> holograms = new HashMap<>();
    private final Map<String, StorageBlock> globalBlocks = new HashMap<>();
    private final Map<String, StorageBlock> personalBlocks = new HashMap<>();
    private BukkitTask hopperTask;

    public StorageBlockManager(VContainer plugin, ContainerManager containerManager) {
        this.plugin = plugin;
        this.containerManager = containerManager;
        this.legacyFile = new File(plugin.getDataFolder(), "storage_blocks.yml");
        this.globalFile = new File(plugin.getDataFolder(), "global_storage_blocks.yml");
        this.personalFile = new File(plugin.getDataFolder(), "personal_storage_blocks.yml");
        load();
        startHopperTask();
    }

    public boolean add(Block block) {
        String key = key(block.getLocation());
        if (globalBlocks.containsKey(key) || personalBlocks.containsKey(key)) return false;

        globalBlocks.put(key, StorageBlock.global(key));
        spawnHologram(block.getLocation());
        saveGlobal();
        return true;
    }

    public boolean addPersonal(Block block, Player owner) {
        String key = key(block.getLocation());
        if (globalBlocks.containsKey(key) || personalBlocks.containsKey(key)) return false;

        personalBlocks.put(key, StorageBlock.personal(key, owner.getUniqueId(), owner.getName()));
        spawnHologram(block.getLocation());
        savePersonal();
        return true;
    }

    public boolean removeGlobal(Block block) {
        if (block == null) return false;
        String key = key(block.getLocation());
        StorageBlock removed = globalBlocks.remove(key);
        if (removed == null) return false;

        removeHologram(block.getLocation());
        saveGlobal();
        return true;
    }

    public boolean removePersonal(String key, boolean keepBlock) {
        StorageBlock removed = personalBlocks.remove(key);
        if (removed == null) return false;

        Location location = locationFromKey(key);
        if (location != null) {
            removeHologram(location);
            if (!keepBlock && location.getWorld() != null) {
                location.getBlock().setType(Material.AIR);
            }
        }

        savePersonal();
        return true;
    }

    public boolean isStorageBlock(Block block) {
        return get(block) != null;
    }

    public StorageBlock get(Block block) {
        if (block == null) return null;
        String key = key(block.getLocation());
        StorageBlock personal = personalBlocks.get(key);
        return personal != null ? personal : globalBlocks.get(key);
    }

    public StorageBlock get(String key) {
        StorageBlock personal = personalBlocks.get(key);
        return personal != null ? personal : globalBlocks.get(key);
    }

    public boolean canAccess(Player player, StorageBlock storageBlock) {
        if (storageBlock == null) return false;
        if (storageBlock.type() == StorageType.GLOBAL) return true;
        if (player.isOp()) return true;
        return storageBlock.ownerId().equals(player.getUniqueId()) || storageBlock.members().contains(player.getUniqueId());
    }

    public boolean isOwner(Player player, StorageBlock storageBlock) {
        return storageBlock != null && storageBlock.type() == StorageType.PERSONAL && storageBlock.ownerId().equals(player.getUniqueId());
    }

    public void toggleMember(String key, Player player) {
        StorageBlock storageBlock = personalBlocks.get(key);
        if (storageBlock == null) return;

        if (!storageBlock.members().remove(player.getUniqueId())) {
            storageBlock.members().add(player.getUniqueId());
        }
        savePersonal();
    }

    public String key(Block block) {
        return key(block.getLocation());
    }

    public void reloadHolograms() {
        removeAllHolograms();
        for (String key : globalBlocks.keySet()) {
            Location location = locationFromKey(key);
            if (location != null) spawnHologram(location);
        }
        for (String key : personalBlocks.keySet()) {
            Location location = locationFromKey(key);
            if (location != null) spawnHologram(location);
        }
    }

    public void removeAllHolograms() {
        for (String key : globalBlocks.keySet()) {
            Location location = locationFromKey(key);
            if (location != null) removeHologram(location);
        }
        for (String key : personalBlocks.keySet()) {
            Location location = locationFromKey(key);
            if (location != null) removeHologram(location);
        }
        holograms.clear();
    }

    public void shutdown() {
        if (hopperTask != null) hopperTask.cancel();
        removeAllHolograms();
    }

    private void load() {
        loadLegacyIfPresent();
        loadGlobal();
        loadPersonal();
        reloadHolograms();
    }

    private void loadLegacyIfPresent() {
        if (!legacyFile.exists() || globalFile.exists() || personalFile.exists()) return;

        FileConfiguration config = YamlConfiguration.loadConfiguration(legacyFile);
        if (config.isList("blocks")) {
            for (String key : config.getStringList("blocks")) {
                globalBlocks.put(key, StorageBlock.global(key));
            }
            saveGlobal();
            return;
        }

        ConfigurationSection section = config.getConfigurationSection("blocks");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String path = "blocks." + key + ".";
            StorageType type = StorageType.valueOf(config.getString(path + "type", "GLOBAL"));
            if (type == StorageType.PERSONAL) {
                UUID ownerId = UUID.fromString(config.getString(path + "owner-id"));
                String ownerName = config.getString(path + "owner-name", "Unknown");
                Set<UUID> members = new HashSet<>();
                for (String member : config.getStringList(path + "members")) members.add(UUID.fromString(member));
                personalBlocks.put(key, new StorageBlock(key, type, ownerId, ownerName, members));
            } else {
                globalBlocks.put(key, StorageBlock.global(key));
            }
        }
        saveGlobal();
        savePersonal();
    }

    private void loadGlobal() {
        if (!globalFile.exists()) return;
        FileConfiguration config = YamlConfiguration.loadConfiguration(globalFile);
        for (String key : config.getStringList("blocks")) {
            globalBlocks.put(key, StorageBlock.global(key));
        }
    }

    private void loadPersonal() {
        if (!personalFile.exists()) return;
        FileConfiguration config = YamlConfiguration.loadConfiguration(personalFile);
        ConfigurationSection section = config.getConfigurationSection("blocks");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            String path = "blocks." + key + ".";
            UUID ownerId = UUID.fromString(config.getString(path + "owner-id"));
            String ownerName = config.getString(path + "owner-name", "Unknown");
            Set<UUID> members = new HashSet<>();
            for (String member : config.getStringList(path + "members")) members.add(UUID.fromString(member));
            personalBlocks.put(key, new StorageBlock(key, StorageType.PERSONAL, ownerId, ownerName, members));
        }
    }

    private void saveGlobal() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("blocks", globalBlocks.keySet().stream().sorted().toList());
        save(config, globalFile);
    }

    private void savePersonal() {
        YamlConfiguration config = new YamlConfiguration();
        for (StorageBlock storageBlock : personalBlocks.values()) {
            String path = "blocks." + storageBlock.key() + ".";
            config.set(path + "owner-id", storageBlock.ownerId().toString());
            config.set(path + "owner-name", storageBlock.ownerName());
            config.set(path + "members", storageBlock.members().stream().map(UUID::toString).sorted().toList());
        }
        save(config, personalFile);
    }

    private void save(YamlConfiguration config, File file) {
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save " + file.getName() + ": " + e.getMessage());
        }
    }

    private void startHopperTask() {
        long interval = Math.max(1L, plugin.getConfig().getLong("storage-block.hoppers.interval-ticks", 8L));
        hopperTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickHoppers, interval, interval);
    }

    private void tickHoppers() {
        if (!plugin.getConfig().getBoolean("storage-block.hoppers.enabled", true)) return;
        for (StorageBlock storageBlock : personalBlocks.values()) {
            Location location = locationFromKey(storageBlock.key());
            if (location == null || location.getWorld() == null || !location.isChunkLoaded()) continue;

            if (plugin.getConfig().getBoolean("storage-block.hoppers.input", true)) {
                tickInputHoppers(location, storageBlock);
            }
            if (plugin.getConfig().getBoolean("storage-block.hoppers.output", true)) {
                tickOutputHopper(location, storageBlock);
            }
        }
    }

    private void tickInputHoppers(Location location, StorageBlock storageBlock) {
        for (BlockFace face : HOPPER_INPUT_FACES) {
            Block hopperBlock = location.getBlock().getRelative(face);
            if (hopperBlock.getType() != Material.HOPPER || !(hopperBlock.getBlockData() instanceof Directional directional)) continue;
            if (hopperBlock.getRelative(directional.getFacing()) == location.getBlock()) {
                moveOneFromHopperToContainer(hopperBlock, storageBlock.ownerId());
            }
        }
    }

    private void tickOutputHopper(Location location, StorageBlock storageBlock) {
        Block hopperBlock = location.getBlock().getRelative(BlockFace.DOWN);
        if (hopperBlock.getType() != Material.HOPPER) return;
        moveOneFromContainerToHopper(hopperBlock, storageBlock.ownerId());
    }

    private void moveOneFromHopperToContainer(Block hopperBlock, UUID ownerId) {
        if (!(hopperBlock.getState() instanceof Hopper hopper)) return;
        Inventory inventory = hopper.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType().isAir()) continue;

            ItemStack moving = item.clone();
            moving.setAmount(1);
            containerManager.addItemToContainer(ownerId, moving);
            item.setAmount(item.getAmount() - 1);
            inventory.setItem(slot, item.getAmount() > 0 ? item : null);
            return;
        }
    }

    private void moveOneFromContainerToHopper(Block hopperBlock, UUID ownerId) {
        if (!(hopperBlock.getState() instanceof Hopper hopper)) return;
        Inventory inventory = hopper.getInventory();
        for (ItemStack stored : containerManager.getAllItemFromContainer(ownerId)) {
            ItemStack moving = stored.clone();
            moving.setAmount(1);
            if (!inventory.addItem(moving).isEmpty()) return;

            ItemStack target = moving.clone();
            target.setAmount(1);
            containerManager.takeItemFromContainer(ownerId, target, 1);
            return;
        }
    }

    private void spawnHologram(Location blockLocation) {
        if (!plugin.getConfig().getBoolean("storage-block.hologram.enabled", true)) return;
        if (blockLocation.getWorld() == null) return;

        removeHologram(blockLocation);

        Location hologramLocation = blockLocation.clone().add(
                0.5,
                plugin.getConfig().getDouble("storage-block.hologram.height", 1.35),
                0.5
        );

        TextDisplay display = (TextDisplay) blockLocation.getWorld().spawnEntity(hologramLocation, EntityType.TEXT_DISPLAY);
        display.addScoreboardTag(HOLOGRAM_TAG);
        display.addScoreboardTag(tagFor(blockLocation));
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(plugin.getConfig().getBoolean("storage-block.hologram.see-through", false));
        display.setShadowed(plugin.getConfig().getBoolean("storage-block.hologram.shadow", true));
        display.text(hologramText(blockLocation));

        holograms.put(key(blockLocation), display.getUniqueId());
    }

    private void removeHologram(Location blockLocation) {
        UUID uuid = holograms.remove(key(blockLocation));
        if (uuid != null) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) entity.remove();
        }

        if (blockLocation.getWorld() == null) return;
        String tag = tagFor(blockLocation);
        for (Entity entity : blockLocation.getWorld().getNearbyEntities(blockLocation.clone().add(0.5, 1.0, 0.5), 1.5, 2.5, 1.5)) {
            if (entity.getScoreboardTags().contains(HOLOGRAM_TAG) && entity.getScoreboardTags().contains(tag)) {
                entity.remove();
            }
        }
    }

    private Component hologramText(Location location) {
        StorageBlock storageBlock = get(location.getBlock());
        List<String> lines = plugin.getConfig().getStringList("storage-block.hologram.lines");
        if (lines.isEmpty()) lines = List.of("&bVContainer", "&7Right click to open");

        String owner = storageBlock != null && storageBlock.ownerName() != null ? storageBlock.ownerName() : "";
        String text = String.join("\n", lines).replace("{owner}", owner);
        return LEGACY.deserialize(VContainer.formatMessage(text));
    }

    private String key(Location location) {
        return location.getWorld().getName()
                + "," + location.getBlockX()
                + "," + location.getBlockY()
                + "," + location.getBlockZ();
    }

    private String tagFor(Location location) {
        return "vcontainer_storage_" + key(location).replace(",", "_").replace("-", "m");
    }

    private Location locationFromKey(String key) {
        String[] parts = key.split(",");
        if (parts.length != 4) return null;

        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;

        try {
            return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public enum StorageType {
        GLOBAL,
        PERSONAL
    }

    public record StorageBlock(String key, StorageType type, UUID ownerId, String ownerName, Set<UUID> members) {
        private static StorageBlock global(String key) {
            return new StorageBlock(key, StorageType.GLOBAL, null, null, new HashSet<>());
        }

        private static StorageBlock personal(String key, UUID ownerId, String ownerName) {
            return new StorageBlock(key, StorageType.PERSONAL, ownerId, ownerName, new HashSet<>());
        }
    }
}
