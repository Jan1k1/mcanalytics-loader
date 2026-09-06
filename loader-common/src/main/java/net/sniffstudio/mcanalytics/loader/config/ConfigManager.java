package net.sniffstudio.mcanalytics.loader.config;

import net.sniffstudio.mcanalytics.loader.api.LoaderLogger;
import net.sniffstudio.mcanalytics.loader.util.FilePermissions;
import net.sniffstudio.mcanalytics.loader.util.TinyJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ConfigManager {

    public static final String DEFAULT_API_URL = "https://mcanalytics.org";
    private static final String CREDENTIAL_FILE_NAME = "credential.json";
    private static final String LEGACY_CREDENTIAL_FILE_NAME = "credentials.json";

    private final Path dataDirectory;
    private final LoaderLogger logger;

    public ConfigManager(Path dataDirectory, LoaderLogger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    public String resolveApiUrl() {
        String envUrl = System.getenv("MCANALYTICS_ENDPOINT_URL");
        if (envUrl == null || envUrl.isBlank()) {
            envUrl = System.getenv("MCANALYTICS_API_URL");
        }
        if (envUrl != null && !envUrl.isBlank()) {
            return trimTrailingSlash(envUrl.trim());
        }

        Path tomlFile = dataDirectory.resolve("config.toml");
        if (Files.isRegularFile(tomlFile)) {
            String val = readKeyFromLineFile(tomlFile, "endpoint_url");
            if (val == null) {
                val = readKeyFromLineFile(tomlFile, "api_url");
            }
            if (val != null && !val.isBlank()) {
                return trimTrailingSlash(val.trim());
            }
        }

        Path ymlFile = dataDirectory.resolve("config.yml");
        if (Files.isRegularFile(ymlFile)) {
            String val = readKeyFromLineFile(ymlFile, "endpoint-url");
            if (val == null) {
                val = readKeyFromLineFile(ymlFile, "api-url");
            }
            if (val != null && !val.isBlank()) {
                return trimTrailingSlash(val.trim());
            }
        }

        return DEFAULT_API_URL;
    }

    public String resolveDashboardUrl(String apiUrl) {
        String envDash = System.getenv("MCANALYTICS_DASHBOARD_URL");
        if (envDash != null && !envDash.isBlank()) {
            return trimTrailingSlash(envDash.trim());
        }
        if (apiUrl.contains("/api")) {
            return apiUrl.substring(0, apiUrl.indexOf("/api"));
        }
        return apiUrl;
    }

    public Optional<LoaderCredentials> readCredentials() {
        Path credFile = dataDirectory.resolve(CREDENTIAL_FILE_NAME);
        if (!Files.isRegularFile(credFile)) {
            credFile = dataDirectory.resolve(LEGACY_CREDENTIAL_FILE_NAME);
        }

        if (Files.isRegularFile(credFile)) {
            try {
                String content = Files.readString(credFile, StandardCharsets.UTF_8);
                Map<String, Object> map = TinyJson.parseObject(content);
                String token = TinyJson.getString(map, "connectorToken");
                String networkId = TinyJson.getString(map, "networkId");
                String serverId = TinyJson.getString(map, "serverId");
                String serverName = TinyJson.getString(map, "serverName");
                String platform = TinyJson.getString(map, "platform");
                String pairedAtStr = TinyJson.getString(map, "pairedAt");

                Instant pairedAt = Instant.now();
                if (pairedAtStr != null && !pairedAtStr.isBlank()) {
                    try {
                        pairedAt = Instant.parse(pairedAtStr);
                    } catch (Exception ignored) {}
                }

                LoaderCredentials creds = new LoaderCredentials(token, networkId, serverId, serverName, platform, pairedAt);
                if (creds.isComplete()) {
                    return Optional.of(creds);
                }
            } catch (Exception e) {
                logger.warn("[MCAnalytics] Failed to parse credential file at " + credFile + ": " + e.getMessage());
            }
        }

        String envToken = System.getenv("MCANALYTICS_API_TOKEN");
        if (envToken != null && !envToken.isBlank() && isValidToken(envToken)) {
            return Optional.of(new LoaderCredentials(envToken.trim(), "env", "env", "server", "unknown", Instant.now()));
        }

        return Optional.empty();
    }

    public void saveCredentials(LoaderCredentials credentials) throws IOException {
        Files.createDirectories(dataDirectory);
        Path target = dataDirectory.resolve(CREDENTIAL_FILE_NAME);
        Path tempFile = dataDirectory.resolve(CREDENTIAL_FILE_NAME + ".tmp." + System.currentTimeMillis());

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("connectorToken", credentials.connectorToken());
        map.put("networkId", credentials.networkId());
        map.put("serverId", credentials.serverId());
        map.put("serverName", credentials.serverName());
        map.put("platform", credentials.platform());
        map.put("pairedAt", credentials.pairedAt() != null ? credentials.pairedAt().toString() : Instant.now().toString());

        String json = TinyJson.toJson(map);
        Files.writeString(tempFile, json, StandardCharsets.UTF_8);
        FilePermissions.restrictToOwner(tempFile);

        try {
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }

        if (!FilePermissions.restrictToOwner(target) && FilePermissions.supportsPosix()) {
            logger.warn("[MCAnalytics] Could not restrict " + target.getFileName() + " to owner-only permissions.");
        }
    }

    public void clearCredentials() {
        Path credFile = dataDirectory.resolve(CREDENTIAL_FILE_NAME);
        if (Files.isRegularFile(credFile)) {
            Path revoked = dataDirectory.resolve(CREDENTIAL_FILE_NAME + ".revoked." + System.currentTimeMillis());
            try {
                Files.move(credFile, revoked, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(credFile);
                } catch (IOException ignored) {}
            }
        }
    }

    private static String trimTrailingSlash(String s) {
        if (s.endsWith("/")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static boolean isValidToken(String token) {
        return token.startsWith("mca_live_") || token.startsWith("mca_test_");
    }

    private static String readKeyFromLineFile(Path path, String key) {
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    continue;
                }
                if (trimmed.startsWith(key)) {
                    int eq = trimmed.indexOf('=');
                    if (eq == -1) {
                        eq = trimmed.indexOf(':');
                    }
                    if (eq != -1) {
                        String val = trimmed.substring(eq + 1).trim();
                        if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                            val = val.substring(1, val.length() - 1);
                        }
                        return val;
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
