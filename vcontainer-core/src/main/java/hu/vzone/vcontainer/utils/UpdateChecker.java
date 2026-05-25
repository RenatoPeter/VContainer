package hu.vzone.vcontainer.utils;

import hu.vzone.vcontainer.VContainer;
import org.bukkit.Bukkit;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Level;

public final class UpdateChecker {

    private static final String METADATA_URL = "https://repo.vzone.hu/releases/hu/vzone/VContainer/maven-metadata.xml";

    private final VContainer plugin;
    private volatile boolean updateAvailable;
    private volatile String latestVersion;
    private volatile boolean checked;

    public UpdateChecker(VContainer plugin) {
        this.plugin = plugin;
    }

    public void checkAsync() {
        if (!plugin.getConfig().getBoolean("update-checker.enabled", true)) {
            updateAvailable = false;
            latestVersion = null;
            checked = false;
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::checkNow);
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public boolean wasChecked() {
        return checked;
    }

    private void checkNow() {
        try {
            String release = fetchLatestRelease();
            checked = true;
            latestVersion = release;
            String current = plugin.getDescription().getVersion();
            updateAvailable = release != null && compareVersions(release, current) > 0;

            if (updateAvailable) {
                plugin.getLogger().warning("A new VContainer version is available: " + release + " (current: " + current + ")");
                plugin.getLogger().warning("Download: https://repo.vzone.hu/releases/hu/vzone/VContainer/");
            } else {
                plugin.getLogger().info("VContainer is up to date. Current version: " + current);
            }
        } catch (Exception ex) {
            checked = true;
            updateAvailable = false;
            latestVersion = null;
            plugin.getLogger().log(Level.WARNING, "Failed to check for VContainer updates.", ex);
        }
    }

    private String fetchLatestRelease() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(METADATA_URL).toURL().openConnection();
        connection.setConnectTimeout((int) Duration.ofSeconds(6).toMillis());
        connection.setReadTimeout((int) Duration.ofSeconds(6).toMillis());
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "VContainer-UpdateChecker/" + plugin.getDescription().getVersion());

        try (InputStream input = connection.getInputStream()) {
            var builderFactory = DocumentBuilderFactory.newInstance();
            builderFactory.setNamespaceAware(false);
            var document = builderFactory.newDocumentBuilder().parse(input);
            var releaseNodes = document.getElementsByTagName("release");
            if (releaseNodes.getLength() == 0) return null;
            String release = releaseNodes.item(0).getTextContent();
            return release == null ? null : release.trim();
        } finally {
            connection.disconnect();
        }
    }

    private int compareVersions(String left, String right) {
        String[] leftParts = normalizeVersion(left).split("\\.");
        String[] rightParts = normalizeVersion(right).split("\\.");
        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i++) {
            int leftValue = i < leftParts.length ? parseVersionPart(leftParts[i]) : 0;
            int rightValue = i < rightParts.length ? parseVersionPart(rightParts[i]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private String normalizeVersion(String version) {
        return version == null ? "0" : version.trim().toLowerCase(Locale.ROOT).replaceAll("[^0-9.]", "");
    }

    private int parseVersionPart(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
