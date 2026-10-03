package net.sniffstudio.mcanalytics.loader.update;

import net.sniffstudio.mcanalytics.loader.util.TinyJson;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads which plugin a jar says it is. The signature proves a jar came from MCAnalytics; this
 * proves it is a loader for this platform and names the version the manifest promised, so a
 * validly signed connector jar or an old loader jar can never be staged as a new loader.
 */
final class LoaderJarDescriptor {

    private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;
    private static final Pattern YAML_NAME = Pattern.compile("(?m)^name:\\s*[\"']?([A-Za-z0-9_.-]+)[\"']?\\s*$");
    private static final Pattern YAML_VERSION = Pattern.compile("(?m)^version:\\s*[\"']?([0-9A-Za-z_.+-]+)[\"']?\\s*$");

    static final String PAPER_PLUGIN_NAME = "MCAnalyticsLoader";
    static final String VELOCITY_PLUGIN_ID = "mcanalytics-loader";

    private LoaderJarDescriptor() {}

    /** The version the jar declares as the MCAnalytics loader for {@code platform}, if it is one. */
    static Optional<String> loaderVersion(byte[] jar, String platform) {
        try {
            if ("paper".equals(platform) || "bungee".equals(platform)) {
                // Paper reads plugin.yml, BungeeCord bungee.yml; both name the loader MCAnalyticsLoader.
                String yaml = readEntry(jar, "paper".equals(platform) ? "plugin.yml" : "bungee.yml");
                if (yaml == null) {
                    return Optional.empty();
                }
                Matcher name = YAML_NAME.matcher(yaml);
                Matcher version = YAML_VERSION.matcher(yaml);
                if (name.find() && PAPER_PLUGIN_NAME.equals(name.group(1)) && version.find()) {
                    return Optional.of(version.group(1));
                }
                return Optional.empty();
            }
            if ("velocity".equals(platform)) {
                String json = readEntry(jar, "velocity-plugin.json");
                if (json == null) {
                    return Optional.empty();
                }
                Map<String, Object> map = TinyJson.parseObject(json);
                if (VELOCITY_PLUGIN_ID.equals(TinyJson.getString(map, "id"))) {
                    return Optional.ofNullable(TinyJson.getString(map, "version"));
                }
            }
        } catch (IOException | RuntimeException e) {
            // Not a readable jar: it is not a loader.
        }
        return Optional.empty();
    }

    private static String readEntry(byte[] jar, String entryName) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entryName.equals(entry.getName())) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    if (out.size() + read > MAX_DESCRIPTOR_BYTES) {
                        return null;
                    }
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
