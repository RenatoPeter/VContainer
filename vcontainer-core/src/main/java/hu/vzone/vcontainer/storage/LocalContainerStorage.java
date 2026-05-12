package hu.vzone.vcontainer.storage;

import com.google.gson.Gson;
import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.utils.ItemUtils;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LocalContainerStorage implements ContainerStorage {
    private final VContainer plugin;
    private final File folder;
    private final File legacyFolder;
    private final Gson gson;

    public LocalContainerStorage(VContainer plugin) {
        this.plugin = plugin;
        this.folder = plugin.getPlayerDataFolder();
        this.legacyFolder = new File(plugin.getDataFolder(), "player_data");
        this.gson = plugin.getGson();
    }

    @Override
    public Map<UUID, List<ItemStack>> loadAll() {
        Map<UUID, List<ItemStack>> containers = new HashMap<>();
        loadFromFolder(folder, containers, false);
        if (!legacyFolder.equals(folder)) {
            loadFromFolder(legacyFolder, containers, true);
        }
        return containers;
    }

    private void loadFromFolder(File sourceFolder, Map<UUID, List<ItemStack>> containers, boolean migrate) {
        File[] files = sourceFolder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;

        for (File file : files) {
            String name = file.getName();
            try {
                UUID ownerId = UUID.fromString(name.substring(0, name.length() - ".json".length()));
                if (containers.containsKey(ownerId)) continue;

                List<ItemStack> items = load(file);
                containers.put(ownerId, items);
                if (migrate) save(ownerId, items);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipping invalid container file: " + name);
            }
        }
    }

    @Override
    public void save(UUID ownerId, List<ItemStack> items) {
        File file = new File(folder, ownerId + ".json");
        try (FileOutputStream fos = new FileOutputStream(file)) {
            Map<String, String> wrapper = new HashMap<>();
            wrapper.put("items_base64", ItemUtils.itemsToBase64(items));
            fos.write(gson.toJson(wrapper).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save local container for " + ownerId + ": " + e.getMessage());
        }
    }

    private List<ItemStack> load(File file) {
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            Map<String, String> wrapper = gson.fromJson(json, Map.class);
            if (wrapper == null) return new ArrayList<>();

            String base64 = wrapper.get("items_base64");
            if (base64 == null || base64.isEmpty()) return new ArrayList<>();
            return ItemUtils.itemsFromBase64(base64);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to load local container " + file.getName() + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void close() {
    }
}
