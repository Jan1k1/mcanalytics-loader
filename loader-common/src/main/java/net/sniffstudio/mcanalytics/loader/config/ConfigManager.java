package net.sniffstudio.mcanalytics.loader.config;

import net.sniffstudio.mcanalytics.loader.api.LoaderLogger;
import net.sniffstudio.mcanalytics.loader.util.FilePermissions;
import net.sniffstudio.mcanalytics.loader.util.TinyJson;

import java.io.IOException;
import java.net.URI;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

public final class ConfigManager {

    /** The only address the loader talks to. Operators cannot change it. */
    public static final String DEFAULT_API_URL = "https://mcanalytics.org";
    static final String PUBLIC_HOST = "mcanalytics.org";

    /**
     * Internal hook for local development and automated tests. Not documented to operators, and
     * honoured only when it points at this machine, so it can never send a server's token to
     * another host.
     */
    static final String INTERNAL_ENDPOINT_PROPERTY = "mcanalytics.internal.testEndpoint";

    /** Address keys older loaders and connectors read. They are ignored now. */
    static final List<String> LEGACY_TOML_ADDRESS_KEYS = List.of("endpoint_url", "api_url", "dashboard_url");
    static final List<String> LEGACY_YML_ADDRESS_KEYS = List.of("endpoint-url", "api-url", "dashboard-url");

    private static final String CREDENTIAL_FILE_NAME = "credential.json";
    private static final String LEGACY_CREDENTIAL_FILE_NAME = "credentials.json";

    private final Path dataDirectory;
    private final LoaderLogger logger;
    private final UnaryOperator<String> systemProperties;
    private final AtomicBoolean ignoredAddressNoticeLogged = new AtomicBoolean(false);

    public ConfigManager(Path dataDirectory, LoaderLogger logger) {
        this(dataDirectory, logger, System::getProperty);
    }

    ConfigManager(Path dataDirectory, LoaderLogger logger, UnaryOperator<String> systemProperties) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.systemProperties = systemProperties != null ? systemProperties : name -> null;
    }

    /**
     * Always {@value #DEFAULT_API_URL}. An address in config.yml, config.toml or the environment
     * is ignored; see {@link #logIgnoredAddressSettingOnce()}.
     */
    public String resolveApiUrl() {
        String internal = internalEndpoint(systemProperties.apply(INTERNAL_ENDPOINT_PROPERTY));
        return internal != null ? internal : DEFAULT_API_URL;
    }

    public String resolveDashboardUrl(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return DEFAULT_API_URL;
        }
        if (apiUrl.contains("/api")) {
            return apiUrl.substring(0, apiUrl.indexOf("/api"));
        }
        return apiUrl;
    }

    /**
     * Says once, at INFO, that an address left in an old config file is not used any more. The
     * value itself is never printed.
     *
     * @return true when the notice was logged by this call
     */
    public boolean logIgnoredAddressSettingOnce() {
        String file = findIgnoredAddressSetting();
        if (file == null || !ignoredAddressNoticeLogged.compareAndSet(false, true)) {
            return false;
        }
        logger.info("[MCAnalytics] The address setting in " + file + " is no longer used. "
                + "MCAnalytics always connects to " + PUBLIC_HOST + ".");
        return true;
    }

    /**
     * @return the name of the first config file in the data folder that still carries an address
     *         key, or null when there is none
     */
    String findIgnoredAddressSetting() {
        Path tomlFile = dataDirectory.resolve("config.toml");
        if (Files.isRegularFile(tomlFile) && containsAnyKey(tomlFile, LEGACY_TOML_ADDRESS_KEYS)) {
            return "config.toml";
        }
        Path ymlFile = dataDirectory.resolve("config.yml");
        if (Files.isRegularFile(ymlFile) && containsAnyKey(ymlFile, LEGACY_YML_ADDRESS_KEYS)) {
            return "config.yml";
        }
        return null;
    }

    /**
     * The internal override, or null when it is unset or points anywhere but this machine.
     */
    static String internalEndpoint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = trimTrailingSlash(value.trim());
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                return null;
            }
            boolean loopback = host.equalsIgnoreCase("localhost")
                    || host.equals("127.0.0.1")
                    || host.equals("[::1]")
                    || host.equals("::1");
            return loopback ? trimmed : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
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
        String result = s;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static boolean isValidToken(String token) {
        return token.startsWith("mca_live_") || token.startsWith("mca_test_");
    }

    /**
     * True when the file sets one of the keys, at any indentation, in either the {@code key: value}
     * or the {@code key = value} form. Comments are skipped. A file that cannot be read has none.
     */
    static boolean containsAnyKey(Path path, List<String> keys) {
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("//")) {
                    continue;
                }
                for (String key : keys) {
                    if (!trimmed.startsWith(key)) {
                        continue;
                    }
                    String rest = trimmed.substring(key.length()).trim();
                    if (rest.startsWith(":") || rest.startsWith("=")) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
