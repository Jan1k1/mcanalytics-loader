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

    private static final String SHA = "ab".repeat(32);
    private static final String SIG = java.util.Base64.getEncoder().encodeToString(new byte[64]);

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
        try {
            server.removeContext(path);
        } catch (IllegalArgumentException ignored) {
            // no context yet
        }
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
                + "\"sha256\":\"" + SHA + "\","
                + "\"sizeBytes\":204800,"
                + "\"downloadPath\":\"/api/v1/connector/release/download?platform=paper\","
                + "\"signature\":\"" + SIG + "\","
                + "\"minLoader\":\"1.2.0\"}}";
        respond("/api/v1/connector/release", 200, json.getBytes(StandardCharsets.UTF_8));

        ReleaseClient client = new ReleaseClient("1.0.0", "paper");
        ReleaseClient.ReleaseCheckResult result = client.checkRelease(baseUrl, "paper", "mca_live_token");

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.metadata()).isNotNull();
        ReleaseClient.ReleaseMetadata meta = result.metadata();
        assertThat(meta.platform()).isEqualTo("paper");
        assertThat(meta.version()).isEqualTo("1.4.2");
        assertThat(meta.sha256()).isEqualTo(SHA);
        assertThat(meta.sizeBytes()).isEqualTo(204800L);
        assertThat(meta.downloadPath()).isEqualTo("/api/v1/connector/release/download?platform=paper");
        assertThat(meta.minLoader()).isEqualTo("1.2.0");
        assertThat(meta.signature()).isEqualTo(SIG);
    }

    @Test
    @DisplayName("Defaults minLoader when the manifest omits it")
    void defaultsMissingMinLoader() {
        String json = "{\"data\":{\"version\":\"2.0.0\",\"sha256\":\"" + SHA + "\",\"sizeBytes\":10,\"signature\":\"" + SIG + "\",\"downloadPath\":\"/dl\"}}";
        respond("/api/v1/connector/release", 200, json.getBytes(StandardCharsets.UTF_8));

        ReleaseClient client = new ReleaseClient("1.0.0", "velocity");
        ReleaseClient.ReleaseCheckResult result = client.checkRelease(baseUrl, "velocity", "mca_live_token");

        assertThat(result.metadata()).isNotNull();
        assertThat(result.metadata().minLoader()).isEqualTo("1.0.0");
        assertThat(result.metadata().sizeBytes()).isEqualTo(10L);
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

        assertThatThrownBy(() -> client.downloadBundle(baseUrl + "/download", "mca_live_token", target, SHA, 10))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("403");
    }

    private ReleaseClient.ReleaseCheckResult check(String dataJson) {
        respond("/api/v1/connector/release", 200, ("{\"data\":" + dataJson + "}").getBytes(StandardCharsets.UTF_8));
        return new ReleaseClient("1.0.0", "paper").checkRelease(baseUrl, "paper", "mca_live_token");
    }

    @Test
    @DisplayName("A release without a sha256 is refused, not treated as unchecked")
    void refusesMissingChecksum() {
        ReleaseClient.ReleaseCheckResult result = check("{\"version\":\"1.0.0\",\"sizeBytes\":10,\"signature\":\"" + SIG + "\",\"downloadPath\":\"/dl\"}");

        assertThat(result.metadata()).isNull();
        assertThat(result.errorMessage()).contains("missing or invalid sha256");
    }

    @Test
    @DisplayName("A release with a malformed sha256 is refused")
    void refusesMalformedChecksum() {
        for (String bad : new String[]{"", "abc123def456", "z".repeat(64), "ab".repeat(32) + "0", "ab".repeat(31)}) {
            ReleaseClient.ReleaseCheckResult result = new ReleaseClient("1.0.0", "paper").checkRelease(
                    serveOnce("{\"version\":\"1.0.0\",\"sha256\":\"" + bad + "\",\"sizeBytes\":10,\"signature\":\"" + SIG + "\",\"downloadPath\":\"/dl\"}"),
                    "paper", "mca_live_token");
            assertThat(result.metadata()).as("sha256 %s", bad).isNull();
        }
    }

    private String serveOnce(String dataJson) {
        respond("/api/v1/connector/release", 200, ("{\"data\":" + dataJson + "}").getBytes(StandardCharsets.UTF_8));
        return baseUrl;
    }

    @Test
    @DisplayName("A release without a size, or with a size of zero or below, is refused")
    void refusesMissingOrZeroSize() {
        String noSize = "{\"version\":\"1.0.0\",\"sha256\":\"" + SHA + "\",\"signature\":\"" + SIG + "\",\"downloadPath\":\"/dl\"}";
        assertThat(check(noSize).metadata()).isNull();
        assertThat(check(noSize).errorMessage()).contains("missing or invalid size");

        for (String size : new String[]{"0", "-5", "\"12\"", "null"}) {
            ReleaseClient.ReleaseCheckResult result = new ReleaseClient("1.0.0", "paper").checkRelease(
                    serveOnce("{\"version\":\"1.0.0\",\"sha256\":\"" + SHA + "\",\"sizeBytes\":" + size + ",\"signature\":\"" + SIG + "\",\"downloadPath\":\"/dl\"}"),
                    "paper", "mca_live_token");
            assertThat(result.metadata()).as("size %s", size).isNull();
        }
    }

    @Test
    @DisplayName("A declared size above the download limit is refused")
    void refusesSizeAboveTheLimit() {
        long tooBig = ReleaseClient.MAX_BUNDLE_BYTES + 1;
        ReleaseClient.ReleaseCheckResult result = check("{\"version\":\"1.0.0\",\"sha256\":\"" + SHA
                + "\",\"sizeBytes\":" + tooBig + ",\"signature\":\"" + SIG + "\",\"downloadPath\":\"/dl\"}");
        assertThat(result.metadata()).isNull();
        assertThat(result.errorMessage()).contains("limit");
    }

    @Test
    @DisplayName("A version that is not x.y.z is refused before it can reach a file name")
    void refusesBadVersion() {
        ReleaseClient.ReleaseCheckResult result = check("{\"version\":\"../../evil\",\"sha256\":\"" + SHA
                + "\",\"sizeBytes\":10,\"signature\":\"" + SIG + "\",\"downloadPath\":\"/dl\"}");
        assertThat(result.metadata()).isNull();
        assertThat(result.errorMessage()).contains("version");
    }

    @Test
    @DisplayName("A reply above the JSON size limit is refused")
    void refusesOversizedReply() {
        String padding = "x".repeat(ReleaseClient.MAX_JSON_BYTES);
        ReleaseClient.ReleaseCheckResult result = check("{\"version\":\"1.0.0\",\"sha256\":\"" + SHA
                + "\",\"sizeBytes\":10,\"signature\":\"" + SIG + "\",\"downloadPath\":\"/dl\",\"note\":\"" + padding + "\"}");
        assertThat(result.metadata()).isNull();
        assertThat(result.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("A reply nested deeper than the limit is refused without a crash")
    void refusesDeeplyNestedReply() {
        String nested = "[".repeat(TinyJsonLimits.DEPTH + 5) + "]".repeat(TinyJsonLimits.DEPTH + 5);
        ReleaseClient.ReleaseCheckResult result = check("{\"version\":\"1.0.0\",\"x\":" + nested + "}");
        assertThat(result.metadata()).isNull();
    }

    private static final class TinyJsonLimits {
        static final int DEPTH = net.sniffstudio.mcanalytics.loader.util.TinyJson.MAX_DEPTH;
    }

    @Test
    @DisplayName("A download with no checksum or no size is never started")
    void downloadRefusesMissingChecksumOrSize(@TempDir Path tempDir) {
        respond("/download", 200, "bytes".getBytes(StandardCharsets.UTF_8));
        ReleaseClient client = new ReleaseClient("1.0.0", "paper");
        Path target = tempDir.resolve("bundle.tmp");

        assertThatThrownBy(() -> client.downloadBundle(baseUrl + "/download", "t", target, null, 5))
                .isInstanceOf(ReleaseClient.ReleaseRejectedException.class);
        assertThatThrownBy(() -> client.downloadBundle(baseUrl + "/download", "t", target, "abc", 5))
                .isInstanceOf(ReleaseClient.ReleaseRejectedException.class);
        assertThatThrownBy(() -> client.downloadBundle(baseUrl + "/download", "t", target, SHA, 0))
                .isInstanceOf(ReleaseClient.ReleaseRejectedException.class);
        assertThatThrownBy(() -> client.downloadBundle(baseUrl + "/download", "t", target, SHA, -1))
                .isInstanceOf(ReleaseClient.ReleaseRejectedException.class);
        assertThatThrownBy(() -> client.downloadBundle(baseUrl + "/download", "t", target, SHA, ReleaseClient.MAX_BUNDLE_BYTES + 1))
                .isInstanceOf(ReleaseClient.ReleaseRejectedException.class);
        assertThat(target).doesNotExist();
    }

    @Test
    @DisplayName("A body longer than the declared size is cut off and deleted, never fully written")
    void downloadStopsAtTheDeclaredSize(@TempDir Path tempDir) throws Exception {
        byte[] body = new byte[200_000];
        server.createContext("/download", exchange -> {
            exchange.sendResponseHeaders(200, 0); // chunked, so no Content-Length to check
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            } catch (IOException ignored) {
            }
        });

        Path target = tempDir.resolve("bundle.tmp");
        assertThatThrownBy(() -> new ReleaseClient("1.0.0", "paper").downloadBundle(
                baseUrl + "/download", "t", target, SHA, 1000))
                .isInstanceOf(ReleaseClient.ReleaseRejectedException.class)
                .hasMessageContaining("size mismatch");
        assertThat(target).doesNotExist();
    }

    @Test
    @DisplayName("A Content-Length above the declared size is refused")
    void downloadRefusesLongerContentLength(@TempDir Path tempDir) {
        respond("/download", 200, new byte[5000]);
        Path target = tempDir.resolve("bundle.tmp");
        assertThatThrownBy(() -> new ReleaseClient("1.0.0", "paper").downloadBundle(
                baseUrl + "/download", "t", target, SHA, 1000))
                .isInstanceOf(ReleaseClient.ReleaseRejectedException.class);
        assertThat(target).doesNotExist();
    }

    @Test
    @DisplayName("A redirect to another host is refused and the token is never sent there")
    void neverFollowsARedirectToAnotherHost(@TempDir Path tempDir) throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> seenAuth = new java.util.concurrent.atomic.AtomicReference<>();
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        other.start();
        try {
            // localhost and 127.0.0.1 are different hosts to the loader, on the same machine.
            String target = "http://localhost:" + other.getAddress().getPort() + "/steal";
            server.createContext("/download", exchange -> {
                exchange.getResponseHeaders().add("Location", target);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/api/v1/connector/release", exchange -> {
                exchange.getResponseHeaders().add("Location", target);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });

            Path tmp = tempDir.resolve("bundle.tmp");
            ReleaseClient client = new ReleaseClient("1.0.0", "paper");
            assertThatThrownBy(() -> client.downloadBundle(baseUrl + "/download", "mca_live_secret", tmp, SHA, 10))
                    .isInstanceOf(ReleaseClient.ReleaseRejectedException.class)
                    .hasMessageContaining("redirect to another host");

            ReleaseClient.ReleaseCheckResult result = client.checkRelease(baseUrl, "paper", "mca_live_secret");
            assertThat(result.metadata()).isNull();
            assertThat(result.statusCode()).isEqualTo(ReleaseClient.STATUS_REFUSED);

            assertThat(seenAuth.get()).isNull();
        } finally {
            other.stop(0);
        }
    }

    @Test
    @DisplayName("A redirect inside the same host still works and keeps the token")
    void followsASameHostRedirect(@TempDir Path tempDir) throws Exception {
        byte[] payload = "the-bundle".getBytes(StandardCharsets.UTF_8);
        java.util.concurrent.atomic.AtomicReference<String> seenAuth = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/final");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/final", exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });

        Path target = tempDir.resolve("bundle.tmp");
        new ReleaseClient("1.0.0", "paper").downloadBundle(baseUrl + "/moved", "mca_live_token", target,
                ChecksumUtil.sha256(payload), payload.length);
        assertThat(seenAuth.get()).isEqualTo("Bearer mca_live_token");
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
    }

    @Test
    @DisplayName("A download path must start with exactly one slash and stay on the API host")
    void resolvesOnlyPathsOnTheApiHost() throws Exception {
        String base = "https://mcanalytics.org";
        assertThat(ReleaseClient.resolveDownloadUri(base, "/api/v1/connector/release/download?platform=paper").toString())
                .isEqualTo("https://mcanalytics.org/api/v1/connector/release/download?platform=paper");
        assertThat(ReleaseClient.resolveDownloadUri(base, "/@evil.example/x").getHost()).isEqualTo("mcanalytics.org");
        assertThat(ReleaseClient.resolveDownloadUri("http://127.0.0.1:8080", "/a/../b").toString())
                .isEqualTo("http://127.0.0.1:8080/b");

        for (String bad : new String[]{"@evil.example/x", "//evil.example/x", "///x", "https://evil.example/x",
                "evil.example/x", "", "/", null, "\\evil.example\\x", "/\\evil.example", "/a b", ".evil.example", "http://mcanalytics.org/x"}) {
            assertThatThrownBy(() -> ReleaseClient.resolveDownloadUri(base, bad))
                    .as("path %s", bad)
                    .isInstanceOf(ReleaseClient.ReleaseRejectedException.class);
        }
    }

    @Test
    @DisplayName("A release without a signature, or with a malformed one, is refused")
    void refusesMissingOrMalformedSignature() {
        String withoutSignature = "{\"version\":\"1.0.0\",\"sha256\":\"" + SHA + "\",\"sizeBytes\":10,\"downloadPath\":\"/dl\"}";
        ReleaseClient.ReleaseCheckResult result = check(withoutSignature);
        assertThat(result.metadata()).isNull();
        assertThat(result.errorMessage()).contains("signature");

        for (String bad : new String[]{"", "not base64!", java.util.Base64.getEncoder().encodeToString(new byte[63]), "null"}) {
            String json = "{\"version\":\"1.0.0\",\"sha256\":\"" + SHA + "\",\"sizeBytes\":10,\"downloadPath\":\"/dl\",\"signature\":"
                    + ("null".equals(bad) ? "null" : "\"" + bad + "\"") + "}";
            assertThat(new ReleaseClient("1.0.0", "paper").checkRelease(serveOnce(json), "paper", "t").metadata())
                    .as("signature %s", bad).isNull();
        }
    }
}
