package net.sniffstudio.mcanalytics.loader.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
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
}
