package hu.vzone.vcontainer.storage;

import com.google.gson.Gson;
import hu.vzone.vcontainer.VContainer;
import hu.vzone.vcontainer.utils.ItemUtils;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/** Emergency local durability for dirty online containers while a plugin is being unloaded. */
public final class ContainerRecoveryJournal {
    private final Gson gson;
    private final Path folder;

    public ContainerRecoveryJournal(VContainer plugin) {
        this.gson = plugin.getGson();
        this.folder = plugin.getStorageFolder().toPath().resolve("recovery");
    }

    public void write(UUID ownerId, List<ItemStack> items) throws IOException {
        Files.createDirectories(folder);
        Path target = file(ownerId);
        Path temporary = folder.resolve(ownerId + ".tmp");
        String encoded = ItemUtils.itemsToBase64(items);
        byte[] json = gson.toJson(new JournalData(encoded)).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(json);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<ItemStack> read(UUID ownerId) throws IOException {
        Path file = file(ownerId);
        if (!Files.exists(file)) return null;
        try {
            JournalData data = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), JournalData.class);
            if (data == null || data.itemsBase64 == null) throw new IOException("Invalid recovery journal format.");
            return ItemUtils.itemsFromBase64(data.itemsBase64);
        } catch (RuntimeException | ClassNotFoundException exception) {
            throw new IOException("Could not decode recovery journal.", exception);
        }
    }

    public void delete(UUID ownerId) throws IOException {
        Files.deleteIfExists(file(ownerId));
    }

    private Path file(UUID ownerId) {
        return folder.resolve(ownerId + ".json");
    }

    private static final class JournalData {
        private final String itemsBase64;

        private JournalData(String itemsBase64) {
            this.itemsBase64 = itemsBase64;
        }
    }
}
