package net.sniffstudio.mcanalytics.loader.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionProblemTest {

    @Test
    @DisplayName("Network failures map to three plain phrases")
    void mapsFailuresToPlainWords() {
        assertThat(ConnectionProblem.describe(new HttpTimeoutException("request timed out"))).isEqualTo("timed out");
        assertThat(ConnectionProblem.describe(new HttpConnectTimeoutException("HTTP connect timed out"))).isEqualTo("timed out");
        assertThat(ConnectionProblem.describe(new UnknownHostException("mcanalytics.org"))).isEqualTo("address lookup failed");

        ConnectException wrappedLookup = new ConnectException();
        wrappedLookup.initCause(new UnknownHostException("mcanalytics.org"));
        assertThat(ConnectionProblem.describe(wrappedLookup)).isEqualTo("address lookup failed");

        assertThat(ConnectionProblem.describe(new ConnectException("Connection refused"))).isEqualTo("connection failed");
        assertThat(ConnectionProblem.describe(new SocketException("Connection reset"))).isEqualTo("connection failed");
        assertThat(ConnectionProblem.describe(new IOException("header parser received no bytes"))).isEqualTo("connection failed");
        assertThat(ConnectionProblem.describe(null)).isEqualTo("connection failed");
    }

    @Test
    @DisplayName("Statuses become an HTTP code and nothing else")
    void describesStatuses() {
        assertThat(ConnectionProblem.describeStatus(502)).isEqualTo("HTTP 502");
        assertThat(ConnectionProblem.describeStatus(0)).isEqualTo("connection failed");
        assertThat(ConnectionProblem.isConnectionStatus(502)).isTrue();
        assertThat(ConnectionProblem.isConnectionStatus(429)).isTrue();
        assertThat(ConnectionProblem.isConnectionStatus(0)).isTrue();
        assertThat(ConnectionProblem.isConnectionStatus(404)).isFalse();
        assertThat(ConnectionProblem.isConnectionStatus(401)).isFalse();
    }
}
