package hu.vzone.vcontainer.utils;

import hu.vzone.vcontainer.VContainer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    public static YamlConfiguration load(VContainer plugin, String resourcePath, File file) {
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            plugin.saveResource(resourcePath, false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = loadDefaults(plugin, resourcePath);
        config.setDefaults(defaults);

        appendMissingKeys(plugin, file, config, defaults);
        return YamlConfiguration.loadConfiguration(file);
    }

    private static YamlConfiguration loadDefaults(VContainer plugin, String resourcePath) {
        try (InputStream stream = plugin.getResource(resourcePath)) {
            if (stream == null) return new YamlConfiguration();
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to load default config resource " + resourcePath + ": " + e.getMessage());
            return new YamlConfiguration();
        }
    }

    private static void appendMissingKeys(VContainer plugin, File file, YamlConfiguration config, YamlConfiguration defaults) {
        Map<String, Object> missing = new LinkedHashMap<>();
        collectMissing("", defaults, config, missing);
        if (missing.isEmpty()) return;

        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            for (Map.Entry<String, Object> entry : missing.entrySet()) {
                insertMissing(lines, config, entry.getKey(), entry.getValue());
                config.set(entry.getKey(), entry.getValue());
            }
            Files.writeString(file.toPath(), String.join(System.lineSeparator(), lines) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to append missing settings to " + file.getName() + ": " + e.getMessage());
        }
    }

    private static void insertMissing(List<String> lines, YamlConfiguration config, String fullPath, Object value) {
        String parent = deepestExistingParent(config, fullPath);
        String relativePath = parent.isBlank() ? fullPath : fullPath.substring(parent.length() + 1);

        YamlConfiguration patch = new YamlConfiguration();
        patch.set(relativePath, value);
        List<String> snippet = patch.saveToString().trim().lines().toList();
        if (snippet.isEmpty()) return;

        if (parent.isBlank()) {
            appendAutoBlock(lines, snippet, 0);
            return;
        }

        SectionBounds bounds = findSection(lines, parent);
        if (bounds == null) {
            appendAutoBlock(lines, snippet, 0);
            return;
        }

        List<String> indented = indent(snippet, bounds.indent() + 2);
        lines.add(bounds.end(), "");
        lines.add(bounds.end() + 1, spaces(bounds.indent() + 2) + "# Automatically added by VContainer config updater.");
        lines.addAll(bounds.end() + 2, indented);
    }

    private static String deepestExistingParent(YamlConfiguration config, String fullPath) {
        String current = fullPath;
        while (current.contains(".")) {
            current = current.substring(0, current.lastIndexOf('.'));
            if (config.isConfigurationSection(current)) return current;
        }
        return "";
    }

    private static SectionBounds findSection(List<String> lines, String path) {
        String[] parts = path.split("\\.");
        int searchFrom = 0;
        int parentIndent = -1;
        SectionBounds current = null;

        for (String part : parts) {
            current = findDirectSection(lines, searchFrom, parentIndent, part);
            if (current == null) return null;
            searchFrom = current.start() + 1;
            parentIndent = current.indent();
        }
        return current;
    }

    private static SectionBounds findDirectSection(List<String> lines, int from, int parentIndent, String key) {
        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int indent = countIndent(line);
            if (parentIndent < 0 && indent != 0) continue;
            if (parentIndent >= 0 && indent <= parentIndent) return null;
            if (parentIndent >= 0 && indent != parentIndent + 2) continue;
            if (line.stripLeading().equals(key + ":")) {
                return new SectionBounds(i, sectionEnd(lines, i, indent), indent);
            }
        }
        return null;
    }

    private static int sectionEnd(List<String> lines, int start, int indent) {
        int end = start + 1;
        while (end < lines.size()) {
            String line = lines.get(end);
            if (!line.isBlank() && !line.stripLeading().startsWith("#") && countIndent(line) <= indent) {
                break;
            }
            end++;
        }
        return end;
    }

    private static void appendAutoBlock(List<String> lines, List<String> snippet, int indent) {
        lines.add("");
        lines.add("# -----------------------------------------------------------------------------");
        lines.add("# VContainer automatically added missing settings from the bundled defaults.");
        lines.add("# -----------------------------------------------------------------------------");
        lines.addAll(indent(snippet, indent));
    }

    private static List<String> indent(List<String> lines, int indent) {
        String spaces = spaces(indent);
        return lines.stream().map(line -> line.isBlank() ? line : spaces + line).toList();
    }

    private static int countIndent(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') count++;
        return count;
    }

    private static String spaces(int count) {
        return " ".repeat(Math.max(0, count));
    }

    private static void collectMissing(String path, ConfigurationSection defaults, YamlConfiguration config, Map<String, Object> missing) {
        for (String key : defaults.getKeys(false)) {
            String fullPath = path.isBlank() ? key : path + "." + key;
            ConfigurationSection child = defaults.getConfigurationSection(key);
            if (child != null) {
                collectMissing(fullPath, child, config, missing);
                continue;
            }

            Object value = defaults.get(key);
            if (!config.contains(fullPath)) {
                missing.put(fullPath, value);
            }
        }
    }

    private record SectionBounds(int start, int end, int indent) {
    }
}
