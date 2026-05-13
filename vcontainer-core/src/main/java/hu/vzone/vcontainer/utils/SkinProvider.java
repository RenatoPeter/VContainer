package hu.vzone.vcontainer.utils;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import hu.vzone.vcontainer.VContainer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SkinProvider {
    private static final Map<UUID, ProfileProperty> TEXTURE_CACHE = new ConcurrentHashMap<>();

    private SkinProvider() {
    }

    public static boolean apply(SkullMeta skullMeta, Player player) {
        if (skullMeta == null || player == null) return false;

        ProfileProperty cached = TEXTURE_CACHE.get(player.getUniqueId());
        if (cached == null) {
            cached = readTexture(player);
            if (cached != null) TEXTURE_CACHE.put(player.getUniqueId(), cached);
        }

        if (cached == null) {
            cached = fallbackTexture();
        }

        if (cached == null) return false;

        try {
            PlayerProfile profile = Bukkit.createProfile(player.getUniqueId(), player.getName());
            profile.setProperty(cached);
            skullMeta.setPlayerProfile(profile);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static void cache(Player player) {
        if (player == null) return;
        ProfileProperty property = readTexture(player);
        if (property != null) TEXTURE_CACHE.put(player.getUniqueId(), property);
    }

    private static ProfileProperty readTexture(Player player) {
        PlayerProfile profile = player.getPlayerProfile();
        for (ProfileProperty property : profile.getProperties()) {
            if ("textures".equals(property.getName())) {
                return property;
            }
        }
        return copyGameProfileTexture(player);
    }

    private static ProfileProperty copyGameProfileTexture(Player player) {
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object gameProfile = handle.getClass().getMethod("getGameProfile").invoke(handle);
            Object properties = gameProfile.getClass().getMethod("getProperties").invoke(gameProfile);
            Object textureProperties = properties.getClass().getMethod("get", Object.class).invoke(properties, "textures");
            if (!(textureProperties instanceof Iterable<?> iterable)) return null;

            for (Object property : iterable) {
                Object valueObject = invokeFirst(property, "value", "getValue");
                if (valueObject == null) return null;
                String value = String.valueOf(valueObject);
                Object signatureValue = invokeFirst(property, "signature", "getSignature");
                String signature = signatureValue == null ? null : String.valueOf(signatureValue);
                return new ProfileProperty("textures", value, signature);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private static ProfileProperty fallbackTexture() {
        VContainer plugin = VContainer.getInstance();
        if (plugin == null) return null;
        String texture = plugin.getConfig().getString("player-heads.fallback-texture", "");
        if (texture == null || texture.isBlank()) return null;
        return new ProfileProperty("textures", texture.trim());
    }

    private static Object invokeFirst(Object target, String... methodNames) throws ReflectiveOperationException {
        for (String methodName : methodNames) {
            try {
                return target.getClass().getMethod(methodName).invoke(target);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
