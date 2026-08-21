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
import java.nio.file.StandardCopyOption;
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
    public PlayerContainerLoadResult load(UUID ownerId) {
        if (ownerId == null) return PlayerContainerLoadResult.failure("Missing owner UUID.");
        File file = new File(folder, ownerId + ".json");
        if (!file.exists() && !legacyFolder.equals(folder)) file = new File(legacyFolder, ownerId + ".json");
        if (!file.exists()) return PlayerContainerLoadResult.success(new ArrayList<>());
        List<ItemStack> items = load(file);
        if (items == null) {
            quarantine(file);
            return PlayerContainerLoadResult.failure("Could not read local container file.");
        }
        return PlayerContainerLoadResult.success(items);
    }

    @Override
    public boolean save(UUID ownerId, List<ItemStack> items) {
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().severe("Could not create local container folder: " + folder.getAbsolutePath());
            return false;
        }

        File file = new File(folder, ownerId + ".json");
        File tempFile = new File(folder, ownerId + ".json.tmp");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            Map<String, String> wrapper = new HashMap<>();
            wrapper.put("items_base64", ItemUtils.itemsToBase64(items));
            fos.write(gson.toJson(wrapper).getBytes(StandardCharsets.UTF_8));
            fos.flush();
            try {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tempFile.toPath());
            } catch (IOException ignored) {
            }
            plugin.getLogger().severe("Failed to save local container for " + ownerId + ": " + e.getMessage());
            return false;
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
            return null;
        }
    }

    private void quarantine(File file) {
        File folder = new File(plugin.getDataFolder(), "repair/corrupt-local-startup");
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().warning("Could not create repair quarantine folder.");
            return;
        }
        try {
            Files.copy(file.toPath(), new File(folder, file.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("Skipped corrupt local container file and copied it to repair/corrupt-local-startup: " + file.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to quarantine corrupt local container " + file.getName() + ": " + e.getMessage());
        }
    }

    @Override
    public void close() {
    }
}
