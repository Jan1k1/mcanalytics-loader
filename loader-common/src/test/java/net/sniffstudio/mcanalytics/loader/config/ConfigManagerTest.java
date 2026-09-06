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
import java.time.Instant;
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
    @DisplayName("Reads the endpoint override from config.toml and config.yml")
    void readsEndpointOverride(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("config.toml"), "endpoint_url = \"https://staging.example.com/\"");
        assertThat(new ConfigManager(tempDir, SILENT).resolveApiUrl()).isEqualTo("https://staging.example.com");

        Files.delete(tempDir.resolve("config.toml"));
        Files.writeString(tempDir.resolve("config.yml"), "endpoint-url: \"https://other.example.com\"");
        assertThat(new ConfigManager(tempDir, SILENT).resolveApiUrl()).isEqualTo("https://other.example.com");
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
}
