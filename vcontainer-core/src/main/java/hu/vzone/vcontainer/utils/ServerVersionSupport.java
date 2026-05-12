package hu.vzone.vcontainer.utils;

import org.bukkit.Bukkit;

import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ServerVersionSupport {
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");
    private static final Set<String> SUPPORTED_VERSIONS = Set.of(
            "1.21",
            "1.21.1",
            "1.21.2",
            "1.21.3",
            "1.21.4",
            "1.21.5",
            "1.21.6",
            "1.21.7",
            "1.21.8",
            "1.21.9",
            "1.21.10",
            "1.21.11",
            "26.1",
            "26.1.1",
            "26.1.2"
    );

    private ServerVersionSupport() {
    }

    public static String currentVersion() {
        String version = Bukkit.getMinecraftVersion();
        if (version == null || version.isBlank()) {
            version = Bukkit.getBukkitVersion();
        }

        Matcher matcher = VERSION_PATTERN.matcher(version);
        return matcher.find() ? matcher.group(1) : version;
    }

    public static boolean isSupported() {
        return SUPPORTED_VERSIONS.contains(currentVersion());
    }

    public static String supportedVersionsText() {
        StringJoiner joiner = new StringJoiner(", ");
        SUPPORTED_VERSIONS.stream().sorted(ServerVersionSupport::compareVersions).forEach(joiner::add);
        return joiner.toString();
    }

    private static int compareVersions(String first, String second) {
        String[] firstParts = first.split("\\.");
        String[] secondParts = second.split("\\.");
        int length = Math.max(firstParts.length, secondParts.length);

        for (int i = 0; i < length; i++) {
            int firstValue = i < firstParts.length ? Integer.parseInt(firstParts[i]) : 0;
            int secondValue = i < secondParts.length ? Integer.parseInt(secondParts[i]) : 0;
            int compare = Integer.compare(firstValue, secondValue);
            if (compare != 0) return compare;
        }
        return 0;
    }
}
