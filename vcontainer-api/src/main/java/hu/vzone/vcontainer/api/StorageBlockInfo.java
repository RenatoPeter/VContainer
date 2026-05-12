package hu.vzone.vcontainer.api;

import java.util.Set;
import java.util.UUID;

public record StorageBlockInfo(
        UUID id,
        String key,
        StorageBlockType type,
        UUID ownerId,
        String ownerName,
        Set<UUID> members
) {
}
