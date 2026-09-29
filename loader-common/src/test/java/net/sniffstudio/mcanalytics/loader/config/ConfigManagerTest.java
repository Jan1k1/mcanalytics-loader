package net.sniffstudio.mcanalytics.loader.config;

import net.sniffstudio.mcanalytics.loader.api.LoaderLogger;
import net.sniffstudio.mcanalytics.loader.util.FilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigManagerTest {

    private static final LoaderLogger SILENT = new LoaderLogger() {
        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {}

        @Override
        public void error(String message) {}

        @Override
        public void error(String message, Throwable throwable) {}
    };

    static boolean posixSupported() {
        return FilePermissions.supportsPosix();
    }

    @Test
    @DisplayName("Falls back to the public endpoint when nothing is configured")
    void defaultsToPublicEndpoint(@TempDir Path tempDir) {
        ConfigManager config = new ConfigManager(tempDir, SILENT);
        assertThat(config.resolveApiUrl()).isEqualTo("https://mcanalytics.org");
    }

    @Test
    @DisplayName("An address left in config.toml or config.yml is ignored")
    void ignoresAddressKeysInOldConfigFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("config.toml"), "endpoint_url = \"https://staging.example.com/\"\napi_url = \"https://x.example\"");
        assertThat(new ConfigManager(tempDir, SILENT).resolveApiUrl()).isEqualTo("https://mcanalytics.org");

        Files.delete(tempDir.resolve("config.toml"));
        Files.writeString(tempDir.resolve("config.yml"), "api-url: \"https://other.example.com\"\nendpoint-url: \"https://other.example.com\"");
        assertThat(new ConfigManager(tempDir, SILENT).resolveApiUrl()).isEqualTo("https://mcanalytics.org");
        assertThat(new ConfigManager(tempDir, SILENT).resolveDashboardUrl("https://mcanalytics.org"))
                .isEqualTo("https://mcanalytics.org");
    }

    @Test
    @DisplayName("An ignored address is reported once, calmly, and its value is never printed")
    void reportsIgnoredAddressOnce(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("config.yml"), "# api-url: \"commented out\"\napi-url: \"https://secret-staging.example.com\"\n");
        List<String> lines = new ArrayList<>();
        ConfigManager config = new ConfigManager(tempDir, recording(lines));

        assertThat(config.logIgnoredAddressSettingOnce()).isTrue();
        assertThat(config.logIgnoredAddressSettingOnce()).isFalse();

        assertThat(lines).containsExactly("INFO [MCAnalytics] The address setting in config.yml is no longer used. "
                + "MCAnalytics always connects to mcanalytics.org.");
    }

    @Test
    @DisplayName("No notice when the config files carry no address, or only a commented one")
    void noNoticeWithoutAddressKey(@TempDir Path tempDir) throws Exception {
        List<String> lines = new ArrayList<>();
        assertThat(new ConfigManager(tempDir, recording(lines)).logIgnoredAddressSettingOnce()).isFalse();

        Files.writeString(tempDir.resolve("config.yml"), "# api-url: \"https://mcanalytics.org\"\nserver-name: \"lobby\"\napi-urlish: 1\n");
        Files.writeString(tempDir.resolve("config.toml"), "server_name = \"proxy\"\n");
        assertThat(new ConfigManager(tempDir, recording(lines)).logIgnoredAddressSettingOnce()).isFalse();
        assertThat(lines).isEmpty();
    }

    @Test
    @DisplayName("The internal test hook is honoured only for this machine")
    void internalEndpointOnlyForLoopback(@TempDir Path tempDir) {
        assertThat(withInternalEndpoint(tempDir, "http://127.0.0.1:43117/").resolveApiUrl())
                .isEqualTo("http://127.0.0.1:43117");
        assertThat(withInternalEndpoint(tempDir, "http://localhost:3000").resolveApiUrl())
                .isEqualTo("http://localhost:3000");
        assertThat(withInternalEndpoint(tempDir, "https://evil.example.com").resolveApiUrl())
                .isEqualTo("https://mcanalytics.org");
        assertThat(withInternalEndpoint(tempDir, "http://10.0.0.5:3000").resolveApiUrl())
                .isEqualTo("https://mcanalytics.org");
        assertThat(withInternalEndpoint(tempDir, "not a url").resolveApiUrl())
                .isEqualTo("https://mcanalytics.org");
    }

    private static ConfigManager withInternalEndpoint(Path tempDir, String value) {
        return new ConfigManager(tempDir, SILENT,
                name -> ConfigManager.INTERNAL_ENDPOINT_PROPERTY.equals(name) ? value : null);
    }

    private static LoaderLogger recording(List<String> lines) {
        return new LoaderLogger() {
            @Override
            public void info(String message) {
                lines.add("INFO " + message);
            }

            @Override
            public void warn(String message) {
                lines.add("WARN " + message);
            }

            @Override
            public void error(String message) {
                lines.add("ERROR " + message);
            }

            @Override
            public void error(String message, Throwable throwable) {
                lines.add("ERROR " + message);
            }
        };
    }

    @Test
    @DisplayName("A fresh data directory reports no credential")
    void freshDirectoryIsUnpaired(@TempDir Path tempDir) {
        assertThat(new ConfigManager(tempDir, SILENT).readCredentials()).isEmpty();
    }

    @Test
    @DisplayName("An incomplete credential file is treated as unpaired")
    void incompleteCredentialIsUnpaired(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("credential.json"), "{\"connectorToken\":\"mca_live_x\"}", StandardCharsets.UTF_8);
        assertThat(new ConfigManager(tempDir, SILENT).readCredentials()).isEmpty();
    }

    @Test
    @DisplayName("A saved credential round-trips through disk")
    void savedCredentialRoundTrips(@TempDir Path tempDir) throws Exception {
        ConfigManager config = new ConfigManager(tempDir, SILENT);
        config.saveCredentials(new LoaderCredentials("mca_live_abc", "net-7", "srv-7", "lobby", "paper", Instant.now()));

        LoaderCredentials read = config.readCredentials().orElseThrow();
        assertThat(read.connectorToken()).isEqualTo("mca_live_abc");
        assertThat(read.networkId()).isEqualTo("net-7");
        assertThat(read.serverId()).isEqualTo("srv-7");
        assertThat(read.serverName()).isEqualTo("lobby");
    }

    @Test
    @DisplayName("Saving leaves no temporary file behind")
    void saveLeavesNoTempFile(@TempDir Path tempDir) throws Exception {
        ConfigManager config = new ConfigManager(tempDir, SILENT);
        config.saveCredentials(new LoaderCredentials("mca_live_abc", "net-7", "srv-7", "lobby", "paper", Instant.now()));

        try (var stream = Files.list(tempDir)) {
            assertThat(stream.map(p -> p.getFileName().toString()))
                    .containsExactly("credential.json");
        }
    }

    @Test
    @EnabledIf("posixSupported")
    @DisplayName("The credential file is written with mode 600")
    void credentialFileIsOwnerOnly(@TempDir Path tempDir) throws Exception {
        ConfigManager config = new ConfigManager(tempDir, SILENT);
        config.saveCredentials(new LoaderCredentials("mca_live_abc", "net-7", "srv-7", "lobby", "paper", Instant.now()));

        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(tempDir.resolve("credential.json"));
        assertThat(permissions).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    @DisplayName("Clearing a credential removes it from the read path")
    void clearCredentialsMakesServerUnpaired(@TempDir Path tempDir) throws Exception {
        ConfigManager config = new ConfigManager(tempDir, SILENT);
        config.saveCredentials(new LoaderCredentials("mca_live_abc", "net-7", "srv-7", "lobby", "paper", Instant.now()));
        config.clearCredentials();

        assertThat(config.readCredentials()).isEmpty();
        assertThat(tempDir.resolve("credential.json")).doesNotExist();
    }

    @Test
    @EnabledIf("posixSupported")
    @DisplayName("A private temp file is owner-only from the moment it exists")
    void privateTempFileIsOwnerOnlyFromCreation(@TempDir Path tempDir) throws Exception {
        Path file = FilePermissions.createPrivateTempFile(tempDir, "credential.json.tmp.", "");

        assertThat(Files.size(file)).isZero();
        assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    @EnabledIf("posixSupported")
    @DisplayName("Saving over an existing wide-open file leaves owner-only permissions and no stray files")
    void saveReplacesAWorldReadableFileWithAPrivateOne(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("credential.json");
        Files.writeString(target, "{}");
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));

        ConfigManager config = new ConfigManager(tempDir, SILENT);
        config.saveCredentials(new LoaderCredentials("mca_live_abc", "net-7", "srv-7", "lobby", "paper", Instant.now()));

        assertThat(Files.getPosixFilePermissions(target)).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        try (var stream = Files.list(tempDir)) {
            assertThat(stream.map(p -> p.getFileName().toString())).containsExactly("credential.json");
        }
    }

    @Test
    @DisplayName("Clearing a credential deletes the token instead of keeping a revoked copy")
    void clearKeepsNoRevokedCopy(@TempDir Path tempDir) throws Exception {
        ConfigManager config = new ConfigManager(tempDir, SILENT);
        config.saveCredentials(new LoaderCredentials("mca_live_abc", "net-7", "srv-7", "lobby", "paper", Instant.now()));
        config.clearCredentials();

        try (var stream = Files.list(tempDir)) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    @DisplayName("Clearing also removes the older credentials.json, which would otherwise be read again")
    void clearRemovesTheLegacyFileToo(@TempDir Path tempDir) throws Exception {
        String token = "{\"connectorToken\":\"mca_live_old\",\"networkId\":\"n\",\"serverId\":\"s\",\"platform\":\"paper\"}";
        Files.writeString(tempDir.resolve("credentials.json"), token);
        ConfigManager config = new ConfigManager(tempDir, SILENT);
        assertThat(config.readCredentials()).isPresent();

        config.clearCredentials();

        assertThat(tempDir.resolve("credentials.json")).doesNotExist();
    }

    @Test
    @DisplayName("Revoked and interrupted copies left by older loaders are removed")
    void staleCopiesAreRemoved(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("credential.json.revoked.1700000000000"), "mca_live_old");
        Files.writeString(tempDir.resolve("credential.json.tmp.1700000000001"), "mca_live_old");
        Files.writeString(tempDir.resolve("config.yml"), "keep: me");

        ConfigManager config = new ConfigManager(tempDir, SILENT);
        assertThat(config.removeStaleCredentialCopies()).isEqualTo(2);

        try (var stream = Files.list(tempDir)) {
            assertThat(stream.map(p -> p.getFileName().toString())).containsExactly("config.yml");
        }
    }

    private boolean autoUpdate(Path dir) {
        return new ConfigManager(dir, SILENT).isAutoUpdateLoaderEnabled();
    }

    @Test
    @DisplayName("Loader self-update is on when there is no config file or no key")
    void autoUpdateDefaultsToOn(@TempDir Path tempDir) throws Exception {
        assertThat(autoUpdate(tempDir)).isTrue();
        Files.writeString(tempDir.resolve("config.yml"), "server-name: \"paper\"\ndebug: false\n");
        assertThat(autoUpdate(tempDir)).isTrue();
        Files.writeString(tempDir.resolve("config.toml"), "server_name = \"proxy\"\n");
        assertThat(autoUpdate(tempDir)).isTrue();
    }

    @Test
    @DisplayName("Loader self-update is off only for an explicit false in config.yml or config.toml")
    void autoUpdateOffOnlyWhenSwitchedOff(@TempDir Path tempDir) throws Exception {
        Path yml = tempDir.resolve("config.yml");
        for (String off : new String[]{"false", "False", "\"false\"", "no", "off", "0", "false   # keep it manual"}) {
            Files.writeString(yml, "auto-update-loader: " + off + "\n");
            assertThat(autoUpdate(tempDir)).as(off).isFalse();
        }
        for (String on : new String[]{"true", "yes", "on", "", "banana"}) {
            Files.writeString(yml, "auto-update-loader: " + on + "\n");
            assertThat(autoUpdate(tempDir)).as("value '" + on + "'").isTrue();
        }
        Files.writeString(yml, "# auto-update-loader: false\n");
        assertThat(autoUpdate(tempDir)).as("a commented line").isTrue();
        Files.delete(yml);
        Files.writeString(tempDir.resolve("config.toml"), "auto_update_loader = false\n");
        assertThat(autoUpdate(tempDir)).as("toml").isFalse();
    }
}
