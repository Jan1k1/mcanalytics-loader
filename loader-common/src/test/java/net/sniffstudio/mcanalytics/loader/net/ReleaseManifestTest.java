package net.sniffstudio.mcanalytics.loader.net;

import com.sun.net.httpserver.HttpServer;
import net.sniffstudio.mcanalytics.loader.util.ChecksumUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseManifestTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void respond(String path, int status, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
    }

    @Test
    @DisplayName("Parses every field of a valid release manifest")
    void parsesValidManifest() {
        String json = "{\"success\":true,\"data\":{"
                + "\"version\":\"1.4.2\","
                + "\"sha256\":\"abc123def456\","
                + "\"sizeBytes\":204800,"
                + "\"downloadPath\":\"/api/v1/connector/release/download?platform=paper\","
                + "\"minLoader\":\"1.2.0\"}}";
        respond("/api/v1/connector/release", 200, json.getBytes(StandardCharsets.UTF_8));

        ReleaseClient client = new ReleaseClient("1.0.0", "paper");
        ReleaseClient.ReleaseCheckResult result = client.checkRelease(baseUrl, "paper", "mca_live_token");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.metadata()).isNotNull();
        ReleaseClient.ReleaseMetadata meta = result.metadata();
        assertThat(meta.platform()).isEqualTo("paper");
        assertThat(meta.version()).isEqualTo("1.4.2");
        assertThat(meta.sha256()).isEqualTo("abc123def456");
        assertThat(meta.sizeBytes()).isEqualTo(204800L);
        assertThat(meta.downloadPath()).isEqualTo("/api/v1/connector/release/download?platform=paper");
        assertThat(meta.minLoader()).isEqualTo("1.2.0");
    }

    @Test
    @DisplayName("Defaults minLoader when the manifest omits it")
    void defaultsMissingMinLoader() {
        String json = "{\"data\":{\"version\":\"2.0.0\",\"sha256\":\"deadbeef\",\"downloadPath\":\"/dl\"}}";
        respond("/api/v1/connector/release", 200, json.getBytes(StandardCharsets.UTF_8));

        ReleaseClient client = new ReleaseClient("1.0.0", "velocity");
        ReleaseClient.ReleaseCheckResult result = client.checkRelease(baseUrl, "velocity", "mca_live_token");

        assertThat(result.metadata()).isNotNull();
        assertThat(result.metadata().minLoader()).isEqualTo("1.0.0");
        assertThat(result.metadata().sizeBytes()).isZero();
    }

    @Test
    @DisplayName("Surfaces the server error message when the manifest request fails")
    void surfacesManifestError() {
        String json = "{\"error\":{\"code\":\"PLAN_REQUIRED\",\"message\":\"Pick a plan first\"}}";
        respond("/api/v1/connector/release", 402, json.getBytes(StandardCharsets.UTF_8));

        ReleaseClient client = new ReleaseClient("1.0.0", "paper");
        ReleaseClient.ReleaseCheckResult result = client.checkRelease(baseUrl, "paper", "mca_live_token");

        assertThat(result.statusCode()).isEqualTo(402);
        assertThat(result.metadata()).isNull();
        assertThat(result.errorMessage()).isEqualTo("Pick a plan first");
    }

    @Test
    @DisplayName("A malformed manifest body yields no metadata instead of throwing")
    void malformedManifestYieldsNoMetadata() {
        respond("/api/v1/connector/release", 200, "not json at all".getBytes(StandardCharsets.UTF_8));

        ReleaseClient client = new ReleaseClient("1.0.0", "paper");
        ReleaseClient.ReleaseCheckResult result = client.checkRelease(baseUrl, "paper", "mca_live_token");

        assertThat(result.metadata()).isNull();
    }

    @Test
    @DisplayName("A download whose checksum matches the manifest is kept")
    void keepsBundleWithMatchingChecksum(@TempDir Path tempDir) throws Exception {
        byte[] payload = "pretend-connector-bundle".getBytes(StandardCharsets.UTF_8);
        respond("/download", 200, payload);

        Path target = tempDir.resolve("bundle.tmp");
        ReleaseClient client = new ReleaseClient("1.0.0", "paper");
        client.downloadBundle(baseUrl + "/download", "mca_live_token", target, ChecksumUtil.sha256(payload), payload.length);

        assertThat(target).exists();
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
    }

    @Test
    @DisplayName("A download whose checksum differs from the manifest is rejected and deleted")
    void rejectsBundleWithChecksumMismatch(@TempDir Path tempDir) {
        byte[] payload = "tampered-connector-bundle".getBytes(StandardCharsets.UTF_8);
        respond("/download", 200, payload);

        Path target = tempDir.resolve("bundle.tmp");
        ReleaseClient client = new ReleaseClient("1.0.0", "paper");

        assertThatThrownBy(() -> client.downloadBundle(
                baseUrl + "/download",
                "mca_live_token",
                target,
                "0000000000000000000000000000000000000000000000000000000000000000",
                payload.length))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Checksum mismatch");

        assertThat(target).doesNotExist();
    }

    @Test
    @DisplayName("A download whose size differs from the manifest is rejected and deleted")
    void rejectsBundleWithSizeMismatch(@TempDir Path tempDir) {
        byte[] payload = "short-body".getBytes(StandardCharsets.UTF_8);
        respond("/download", 200, payload);

        Path target = tempDir.resolve("bundle.tmp");
        ReleaseClient client = new ReleaseClient("1.0.0", "paper");

        assertThatThrownBy(() -> client.downloadBundle(
                baseUrl + "/download",
                "mca_live_token",
                target,
                ChecksumUtil.sha256(payload),
                payload.length + 100))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("size mismatch");

        assertThat(target).doesNotExist();
    }

    @Test
    @DisplayName("A non-200 download response is rejected")
    void rejectsFailedDownload(@TempDir Path tempDir) {
        respond("/download", 403, "denied".getBytes(StandardCharsets.UTF_8));

        Path target = tempDir.resolve("bundle.tmp");
        ReleaseClient client = new ReleaseClient("1.0.0", "paper");

        assertThatThrownBy(() -> client.downloadBundle(baseUrl + "/download", "mca_live_token", target, null, 0))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("403");
    }
}
