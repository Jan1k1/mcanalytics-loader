package net.sniffstudio.mcanalytics.loader.net;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseClientTest {

    @Test
    @DisplayName("Refuses non-https endpoint URLs unless host is localhost or 127.0.0.1")
    void testValidateEndpointUrl() {
        assertThatThrownBy(() -> ReleaseClient.validateEndpointUrl("http://insecure.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");

        assertThatThrownBy(() -> ReleaseClient.validateEndpointUrl(URI.create("http://insecure.example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");

        assertThatThrownBy(() -> ReleaseClient.validateEndpointUrl("ftp://localhost"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ReleaseClient.validateEndpointUrl((String) null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> ReleaseClient.validateEndpointUrl(""))
                .isInstanceOf(IllegalArgumentException.class);

        // Localhost and 127.0.0.1 and HTTPS are valid
        ReleaseClient.validateEndpointUrl("http://localhost:3000");
        ReleaseClient.validateEndpointUrl("http://127.0.0.1:8080");
        ReleaseClient.validateEndpointUrl("https://mcanalytics.org");
    }

    @Test
    @DisplayName("Constructor with invalid endpoint URL throws IllegalArgumentException")
    void testConstructorWithEndpointUrl() {
        assertThatThrownBy(() -> new ReleaseClient("1.0.0", "paper", "http://insecure.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");

        ReleaseClient client = new ReleaseClient("1.0.0", "paper", "https://mcanalytics.org");
        assertThat(client).isNotNull();
    }

    @Test
    @DisplayName("pair, checkRelease, and downloadBundle refuse invalid URLs before sending")
    void testMethodsRefuseInvalidUrls() {
        ReleaseClient client = new ReleaseClient("1.0.0", "paper");

        assertThatThrownBy(() -> client.pair("http://insecure.example.com", "code", "paper"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> client.checkRelease("http://insecure.example.com", "paper", "token"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> client.downloadBundle("http://insecure.example.com/bundle.jar", "token", Path.of("temp.jar"), "hash", 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A 5xx from the edge becomes the status alone, never the body it came with")
    void serverErrorCarriesNoBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/connector/release", exchange -> {
            byte[] body = "error code: 502\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ReleaseClient.ReleaseCheckResult result = new ReleaseClient("1.0.0", "paper")
                    .checkRelease(base, "paper", "mca_live_test");

            assertThat(result.statusCode()).isEqualTo(502);
            assertThat(result.metadata()).isNull();
            assertThat(result.errorMessage()).isEqualTo("HTTP 502");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("An unanswered release check says what happened in plain words")
    void unansweredCheckIsPlainWords() {
        ReleaseClient.ReleaseCheckResult result = new ReleaseClient("1.0.0", "paper")
                .checkRelease("http://127.0.0.1:1", "paper", "mca_live_test");

        assertThat(result.statusCode()).isZero();
        assertThat(result.errorMessage()).isEqualTo("connection failed");
    }

    @Test
    @DisplayName("Pairing against an unreachable site keeps the code and says so plainly")
    void pairingWhileUnreachableIsPlainWords() {
        ReleaseClient.PairResult result = new ReleaseClient("1.0.0", "paper")
                .pair("http://127.0.0.1:1", "ABCD-1234", "paper");

        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).isEqualTo(
                "Cannot reach mcanalytics.org right now (connection failed). Your code was not used, so try again in a minute.");
    }

    @Test
    @DisplayName("A download that gets a 5xx fails with the status only")
    void downloadStatusFailureCarriesStatus(@TempDir Path tempDir) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/connector/release/download", exchange -> {
            byte[] body = "error code: 503\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/connector/release/download";
            assertThatThrownBy(() -> new ReleaseClient("1.0.0", "paper")
                    .downloadBundle(url, "mca_live_test", tempDir.resolve("bundle.tmp"), null, 0))
                    .isInstanceOfSatisfying(ReleaseClient.DownloadStatusException.class,
                            e -> assertThat(e.statusCode()).isEqualTo(503));
        } finally {
            server.stop(0);
        }
    }
}
