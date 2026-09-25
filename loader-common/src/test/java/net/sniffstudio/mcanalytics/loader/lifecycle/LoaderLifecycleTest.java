package net.sniffstudio.mcanalytics.loader.lifecycle;

import com.sun.net.httpserver.HttpServer;
import net.sniffstudio.mcanalytics.loader.api.CommandDelegate;
import net.sniffstudio.mcanalytics.loader.api.CommandSender;
import net.sniffstudio.mcanalytics.loader.api.LoaderLogger;
import net.sniffstudio.mcanalytics.loader.api.PlatformHandle;
import net.sniffstudio.mcanalytics.loader.config.ConfigManager;
import net.sniffstudio.mcanalytics.loader.config.LoaderCredentials;
import net.sniffstudio.mcanalytics.loader.api.ConnectorEntrypoint;
import net.sniffstudio.mcanalytics.loader.util.ChecksumUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LoaderLifecycleTest {

    private HttpServer server;
    private int port;
    private final List<String> loggedMessages = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.start();
        loggedMessages.clear();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private PlatformHandle createMockHandle(Path dataDir, String platform) {
        return new PlatformHandle() {
            private CommandDelegate delegate;

            @Override
            public String platform() {
                return platform;
            }

            @Override
            public String loaderVersion() {
                return "1.0.0";
            }

            @Override
            public Path dataDirectory() {
                return dataDir;
            }

            @Override
            public LoaderLogger logger() {
                return new LoaderLogger() {
                    @Override
                    public void info(String message) {
                        loggedMessages.add("[INFO] " + message);
                    }

                    @Override
                    public void warn(String message) {
                        loggedMessages.add("[WARN] " + message);
                    }

                    @Override
                    public void error(String message) {
                        loggedMessages.add("[ERROR] " + message);
                    }

                    @Override
                    public void error(String message, Throwable throwable) {
                        loggedMessages.add("[ERROR] " + message + ": " + throwable.getMessage());
                    }
                };
            }

            @Override
            public String serverBrand() {
                return "MockServer";
            }

            @Override
            public String serverVersion() {
                return "1.20.4";
            }

            @Override
            public Executor asyncExecutor() {
                return Runnable::run;
            }

            @Override
            public Object rawServer() {
                return null;
            }

            @Override
            public Object rawPlugin() {
                return null;
            }

            @Override
            public void setCommandDelegate(CommandDelegate delegate) {
                this.delegate = delegate;
            }

            @Override
            public CommandDelegate getCommandDelegate() {
                return delegate;
            }
        };
    }

    /** A lifecycle that talks to the local mock server instead of the locked public address. */
    private LoaderLifecycle localLifecycle(PlatformHandle handle) {
        return new LoaderLifecycle(handle, "http://127.0.0.1:" + port, Clock.systemUTC());
    }

    /** A clock the retry and reminder tests can move by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-25T20:28:57Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private static final class TestSender implements CommandSender {
        final List<String> messages = new ArrayList<>();

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }

        @Override
        public boolean hasPermission(String permission) {
            return true;
        }

        @Override
        public boolean isConsole() {
            return true;
        }
    }

    @Test
    void unPairedServerLogsPromptAndSetsUnpairedState(@TempDir Path tempDir) {
        PlatformHandle handle = createMockHandle(tempDir, "velocity");
        LoaderLifecycle lifecycle = localLifecycle(handle);

        lifecycle.onEnable();

        assertThat(lifecycle.getState()).isEqualTo(LoaderLifecycle.State.UNPAIRED);
        assertThat(loggedMessages).anyMatch(msg -> msg.contains("Server is not paired"));
    }

    @Test
    void planRequired402PausesWithoutDownloading(@TempDir Path tempDir) throws Exception {
        server.createContext("/api/v1/connector/release", exchange -> {
            byte[] response = "{\"error\":{\"code\":\"PLAN_REQUIRED\",\"message\":\"Plan required\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(402, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PlatformHandle handle = createMockHandle(tempDir, "velocity");
        ConfigManager config = new ConfigManager(tempDir, handle.logger());
        config.saveCredentials(new LoaderCredentials("mca_live_test", "net-1", "srv-1", "lobby", "velocity", Instant.now()));

        LoaderLifecycle lifecycle = localLifecycle(handle);
        lifecycle.onEnable();

        assertThat(lifecycle.getState()).isEqualTo(LoaderLifecycle.State.PAUSED_NO_PLAN);
        assertThat(loggedMessages).anyMatch(msg -> msg.contains("Network has no active plan"));
    }

    @Test
    void tokenRevoked401ClearsCredentialsAndResetsState(@TempDir Path tempDir) throws Exception {
        server.createContext("/api/v1/connector/release", exchange -> {
            byte[] response = "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Revoked\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PlatformHandle handle = createMockHandle(tempDir, "paper");
        ConfigManager config = new ConfigManager(tempDir, handle.logger());
        config.saveCredentials(new LoaderCredentials("mca_live_revoked", "net-1", "srv-1", "lobby", "paper", Instant.now()));

        LoaderLifecycle lifecycle = localLifecycle(handle);
        lifecycle.onEnable();

        assertThat(lifecycle.getState()).isEqualTo(LoaderLifecycle.State.UNPAIRED);
        assertThat(loggedMessages).anyMatch(msg -> msg.contains("Connector token was revoked"));
        assertThat(config.readCredentials()).isEmpty();
    }

    @Test
    void unreachableApiFallsBackToCachedBundle(@TempDir Path tempDir) throws Exception {
        // Create an existing cached bundle
        Path cacheDir = tempDir.resolve("cache");
        Files.createDirectories(cacheDir);
        Path cachedBundle = cacheDir.resolve("connector-velocity-1.0.0.jar");
        Files.writeString(cachedBundle, "dummy-jar-content");

        PlatformHandle handle = createMockHandle(tempDir, "velocity");
        ConfigManager config = new ConfigManager(tempDir, handle.logger());
        config.saveCredentials(new LoaderCredentials("mca_live_test", "net-1", "srv-1", "lobby", "velocity", Instant.now()));

        // Nothing listens on port 1.
        LoaderLifecycle lifecycle = new LoaderLifecycle(handle, "http://127.0.0.1:1", Clock.systemUTC());
        lifecycle.onEnable();

        assertThat(loggedMessages).anyMatch(msg -> msg.equals("[INFO] [MCAnalytics] Cannot reach mcanalytics.org right now "
                + "(connection failed). Starting the connector already saved on this server "
                + "(connector-velocity-1.0.0.jar). Updates are checked again at the next restart."));
        assertThat(loggedMessages).noneMatch(msg -> msg.startsWith("[WARN]") && msg.contains("Cannot reach"));
        assertThat(loggedMessages).noneMatch(msg -> msg.contains("ConnectException") || msg.contains("Connection refused"));
    }

    @Test
    void serverErrorWithoutACachedBundleWarnsOnceCalmlyAndRetries(@TempDir Path tempDir) throws Exception {
        AtomicInteger releaseStatus = new AtomicInteger(502);
        server.createContext("/api/v1/connector/release", exchange -> {
            int status = releaseStatus.get();
            byte[] response = status == 402
                    ? "{\"error\":{\"code\":\"PLAN_REQUIRED\",\"message\":\"Plan required\"}}".getBytes(StandardCharsets.UTF_8)
                    : "error code: 502\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PlatformHandle handle = createMockHandle(tempDir, "paper");
        LoaderCredentials credentials = new LoaderCredentials("mca_live_test", "net-1", "srv-1", "lobby", "paper", Instant.now());
        new ConfigManager(tempDir, handle.logger()).saveCredentials(credentials);

        MutableClock clock = new MutableClock();
        LoaderLifecycle lifecycle = new LoaderLifecycle(handle, "http://127.0.0.1:" + port, clock);
        try {
            lifecycle.onEnable();

            assertThat(lifecycle.getState()).isEqualTo(LoaderLifecycle.State.FAILED);
            assertThat(lifecycle.isOfflineRetryScheduled()).isTrue();
            assertThat(loggedMessages).containsExactly("[WARN] [MCAnalytics] Cannot reach mcanalytics.org right now (HTTP 502). "
                    + "Your server is fine. Analytics starts as soon as the connector can be downloaded, and the loader "
                    + "tries again every 2 minutes. If this lasts more than 30 minutes, open a ticket in our Discord: "
                    + "https://discord.gg/9MWENuGmYn");
            assertThat(loggedMessages).noneMatch(msg -> msg.contains("error code") || msg.contains("\n"));

            // The retries inside the next half hour stay quiet.
            clock.advance(Duration.ofMinutes(2));
            lifecycle.runReleaseCheckAndLoad(credentials);
            clock.advance(Duration.ofMinutes(2));
            lifecycle.runReleaseCheckAndLoad(credentials);
            assertThat(loggedMessages).hasSize(1);

            // Past half an hour, one short reminder.
            clock.advance(Duration.ofMinutes(27));
            lifecycle.runReleaseCheckAndLoad(credentials);
            assertThat(loggedMessages).hasSize(2);
            assertThat(loggedMessages.get(1)).isEqualTo("[WARN] [MCAnalytics] Still cannot reach mcanalytics.org (HTTP 502). "
                    + "Trying again every 2 minutes. Discord: https://discord.gg/9MWENuGmYn");

            // The site answers again: one line that says so.
            releaseStatus.set(402);
            lifecycle.runReleaseCheckAndLoad(credentials);
            assertThat(loggedMessages).contains("[INFO] [MCAnalytics] Connection to mcanalytics.org is back.");
            assertThat(lifecycle.getState()).isEqualTo(LoaderLifecycle.State.PAUSED_NO_PLAN);
            assertThat(lifecycle.isOfflineRetryScheduled()).isFalse();
            assertThat(loggedMessages).noneMatch(msg -> msg.startsWith("[ERROR]"));
        } finally {
            lifecycle.onDisable();
        }
    }

    @Test
    void serverErrorWithACachedBundleStartsItWithOneInfoLine(@TempDir Path tempDir) throws Exception {
        server.createContext("/api/v1/connector/release", exchange -> {
            byte[] response = "error code: 502\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        Path cacheDir = tempDir.resolve("cache");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("connector-paper-1.0.9.jar"), "dummy-jar-content");

        PlatformHandle handle = createMockHandle(tempDir, "paper");
        new ConfigManager(tempDir, handle.logger())
                .saveCredentials(new LoaderCredentials("mca_live_test", "net-1", "srv-1", "lobby", "paper", Instant.now()));

        LoaderLifecycle lifecycle = localLifecycle(handle);
        lifecycle.onEnable();

        assertThat(loggedMessages).contains("[INFO] [MCAnalytics] Cannot reach mcanalytics.org right now (HTTP 502). "
                + "Starting the connector already saved on this server (connector-paper-1.0.9.jar). "
                + "Updates are checked again at the next restart.");
        assertThat(loggedMessages).noneMatch(msg -> msg.startsWith("[WARN]"));
        assertThat(loggedMessages).noneMatch(msg -> msg.contains("error code"));
        assertThat(lifecycle.isOfflineRetryScheduled()).isFalse();
    }

    @Test
    void pairingCommandFlowSendsRequestAndSavesCredentials(@TempDir Path tempDir) throws Exception {
        server.createContext("/api/v1/connector/pair", exchange -> {
            String json = "{\"success\":true,\"data\":{\"connectorToken\":\"mca_live_paired123\",\"networkId\":\"net-99\",\"serverId\":\"srv-99\",\"serverName\":\"paper-backend\",\"platform\":\"paper\"}}";
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        server.createContext("/api/v1/connector/release", exchange -> {
            byte[] response = "{\"error\":{\"code\":\"PLAN_REQUIRED\",\"message\":\"Plan required\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(402, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PlatformHandle handle = createMockHandle(tempDir, "paper");

        LoaderLifecycle lifecycle = localLifecycle(handle);
        lifecycle.onEnable();

        TestSender sender = new TestSender();
        boolean handled = lifecycle.handleCommand(sender, new String[]{"pair", "ABCD-1234"});

        assertThat(handled).isTrue();
        assertThat(sender.messages).anyMatch(msg -> msg.contains("Successfully paired server"));

        ConfigManager config = new ConfigManager(tempDir, handle.logger());
        LoaderCredentials creds = config.readCredentials().orElseThrow();
        assertThat(creds.connectorToken()).isEqualTo("mca_live_paired123");
        assertThat(creds.networkId()).isEqualTo("net-99");
    }

    @Test
    void pairingWhileActiveStopsRunningBundleAndClosesClassLoader(@TempDir Path tempDir) throws Exception {
        server.createContext("/api/v1/connector/pair", exchange -> {
            String json = "{\"success\":true,\"data\":{\"connectorToken\":\"mca_live_new_paired\",\"networkId\":\"net-active\",\"serverId\":\"srv-active\",\"serverName\":\"paper-backend\",\"platform\":\"paper\"}}";
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        server.createContext("/api/v1/connector/release", exchange -> {
            byte[] response = "{\"error\":{\"code\":\"PLAN_REQUIRED\",\"message\":\"Plan required\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(402, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PlatformHandle handle = createMockHandle(tempDir, "paper");

        LoaderLifecycle lifecycle = localLifecycle(handle);

        AtomicBoolean stopped = new AtomicBoolean(false);
        ConnectorEntrypoint fakeEntrypoint = new ConnectorEntrypoint() {
            @Override
            public void start(PlatformHandle h) {}

            @Override
            public void stop() {
                stopped.set(true);
            }
        };

        AtomicBoolean closed = new AtomicBoolean(false);
        Path dummyJar = tempDir.resolve("dummy.jar");
        Files.writeString(dummyJar, "dummy");
        ConnectorClassLoader fakeClassLoader = new ConnectorClassLoader(dummyJar, getClass().getClassLoader()) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        handle.setCommandDelegate((sender, args) -> false);
        lifecycle.setActiveBundle(fakeEntrypoint, fakeClassLoader);

        assertThat(lifecycle.getState()).isEqualTo(LoaderLifecycle.State.ACTIVE);
        assertThat(handle.getCommandDelegate()).isNotNull();

        TestSender sender = new TestSender();
        boolean handled = lifecycle.handleCommand(sender, new String[]{"pair", "CODE-1234"});

        assertThat(handled).isTrue();
        assertThat(stopped.get()).isTrue();
        assertThat(closed.get()).isTrue();
        assertThat(handle.getCommandDelegate()).isNull();

        ConfigManager config = new ConfigManager(tempDir, handle.logger());
        LoaderCredentials creds = config.readCredentials().orElseThrow();
        assertThat(creds.connectorToken()).isEqualTo("mca_live_new_paired");
        assertThat(creds.networkId()).isEqualTo("net-active");
    }

    @Test
    void addressInAnOldConfigIsIgnoredAndReportedOnce(@TempDir Path tempDir) throws Exception {
        server.createContext("/api/v1/connector/release", exchange -> {
            byte[] response = "{\"error\":{\"code\":\"PLAN_REQUIRED\",\"message\":\"Plan required\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(402, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PlatformHandle handle = createMockHandle(tempDir, "paper");
        ConfigManager config = new ConfigManager(tempDir, handle.logger());
        config.saveCredentials(new LoaderCredentials("mca_live_test", "net-1", "srv-1", "lobby", "paper", Instant.now()));

        // An address older loaders honoured, and one they would have refused outright.
        Files.writeString(tempDir.resolve("config.yml"), "endpoint-url: \"http://10.0.0.5:3000\"");

        LoaderLifecycle lifecycle = localLifecycle(handle);
        lifecycle.onEnable();
        lifecycle.onEnable();

        assertThat(lifecycle.getState()).isEqualTo(LoaderLifecycle.State.PAUSED_NO_PLAN);
        assertThat(loggedMessages)
                .filteredOn(msg -> msg.equals("[INFO] [MCAnalytics] The address setting in config.yml is no longer used. "
                        + "MCAnalytics always connects to mcanalytics.org."))
                .hasSize(1);
        assertThat(loggedMessages).noneMatch(msg -> msg.contains("10.0.0.5"));
        assertThat(loggedMessages).noneMatch(msg -> msg.startsWith("[ERROR]"));
        lifecycle.onDisable();
    }

    @Test
    void pairFailsWhileActiveLeavesRunningBundleUntouched(@TempDir Path tempDir) throws Exception {
        server.createContext("/api/v1/connector/pair", exchange -> {
            byte[] response = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"Pairing code not found or expired\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PlatformHandle handle = createMockHandle(tempDir, "paper");

        LoaderLifecycle lifecycle = localLifecycle(handle);

        AtomicBoolean stopped = new AtomicBoolean(false);
        ConnectorEntrypoint fakeEntrypoint = new ConnectorEntrypoint() {
            @Override
            public void start(PlatformHandle h) {}

            @Override
            public void stop() {
                stopped.set(true);
            }
        };

        AtomicBoolean closed = new AtomicBoolean(false);
        Path dummyJar = tempDir.resolve("dummy.jar");
        Files.writeString(dummyJar, "dummy");
        ConnectorClassLoader fakeClassLoader = new ConnectorClassLoader(dummyJar, getClass().getClassLoader()) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        CommandDelegate originalDelegate = (sender, args) -> false;
        handle.setCommandDelegate(originalDelegate);
        lifecycle.setActiveBundle(fakeEntrypoint, fakeClassLoader);

        assertThat(lifecycle.getState()).isEqualTo(LoaderLifecycle.State.ACTIVE);
        assertThat(handle.getCommandDelegate()).isSameAs(originalDelegate);

        TestSender sender = new TestSender();
        boolean handled = lifecycle.handleCommand(sender, new String[]{"pair", "EXPIRED-1234"});

        assertThat(handled).isTrue();
        assertThat(stopped.get()).isFalse();
        assertThat(closed.get()).isFalse();
        assertThat(lifecycle.getState()).isEqualTo(LoaderLifecycle.State.ACTIVE);
        assertThat(handle.getCommandDelegate()).isSameAs(originalDelegate);
        assertThat(sender.messages).anyMatch(msg -> msg.contains("Pairing code not found or expired") || msg.contains("404"));

        ConfigManager config = new ConfigManager(tempDir, handle.logger());
        assertThat(config.readCredentials()).isEmpty();
    }

    @Test
    void unPairedStartupRegistersPairCommandAndAnswersEveryOtherSubcommand(@TempDir Path tempDir) {
        PlatformHandle handle = createMockHandle(tempDir, "paper");
        LoaderLifecycle lifecycle = localLifecycle(handle);

        lifecycle.onEnable();

        assertThat(lifecycle.getState()).isEqualTo(LoaderLifecycle.State.UNPAIRED);
        assertThat(loggedMessages)
                .filteredOn(msg -> msg.contains("Server is not paired"))
                .hasSize(1);

        TestSender sender = new TestSender();
        assertThat(lifecycle.handleCommand(sender, new String[0])).isTrue();
        assertThat(sender.messages).anyMatch(msg -> msg.contains("/mca pair <code>"));
    }

    @Test
    void unPairedStartupNeverContactsTheReleaseApi(@TempDir Path tempDir) throws Exception {
        AtomicBoolean released = new AtomicBoolean(false);
        server.createContext("/api/v1/connector/release", exchange -> {
            released.set(true);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });

        PlatformHandle handle = createMockHandle(tempDir, "paper");

        LoaderLifecycle lifecycle = localLifecycle(handle);
        lifecycle.onEnable();

        assertThat(lifecycle.getState()).isEqualTo(LoaderLifecycle.State.UNPAIRED);
        assertThat(released.get()).isFalse();
        assertThat(tempDir.resolve("cache")).doesNotExist();
    }

    @Test
    void statusOnAnUnpairedServerReportsStateAndEndpointWithoutAToken(@TempDir Path tempDir) throws Exception {
        PlatformHandle handle = createMockHandle(tempDir, "velocity");
        Files.writeString(tempDir.resolve("config.toml"), "endpoint_url = \"https://staging.example.com\"");

        // The public constructor: the address in config.toml must not be used.
        LoaderLifecycle lifecycle = new LoaderLifecycle(handle);
        lifecycle.onEnable();

        TestSender sender = new TestSender();
        assertThat(lifecycle.handleCommand(sender, new String[]{"status"})).isTrue();

        assertThat(sender.messages).anyMatch(msg -> msg.contains("Loader 1.0.0 on velocity"));
        assertThat(sender.messages).anyMatch(msg -> msg.contains("State: UNPAIRED"));
        assertThat(sender.messages).anyMatch(msg -> msg.contains("Endpoint: https://mcanalytics.org."));
        assertThat(sender.messages).noneMatch(msg -> msg.contains("staging.example.com"));
        assertThat(sender.messages).anyMatch(msg -> msg.contains("Bundle: none loaded"));
        assertThat(loggedMessages).contains("[INFO] [MCAnalytics] The address setting in config.toml is no longer used. "
                + "MCAnalytics always connects to mcanalytics.org.");
    }

    @Test
    void statusOnAPairedServerNamesTheNetworkButNeverTheToken(@TempDir Path tempDir) throws Exception {
        PlatformHandle handle = createMockHandle(tempDir, "paper");
        ConfigManager config = new ConfigManager(tempDir, handle.logger());
        config.saveCredentials(new LoaderCredentials("mca_live_secret_value", "net-42", "srv-42", "lobby", "paper", Instant.now()));

        LoaderLifecycle lifecycle = localLifecycle(handle);

        TestSender sender = new TestSender();
        assertThat(lifecycle.handleCommand(sender, new String[]{"status"})).isTrue();

        assertThat(sender.messages).anyMatch(msg -> msg.contains("Paired as lobby (network net-42)"));
        assertThat(sender.messages).noneMatch(msg -> msg.contains("mca_live_secret_value"));
        assertThat(loggedMessages).noneMatch(msg -> msg.contains("mca_live_secret_value"));
    }

    @Test
    void updateOnAnUnpairedServerAsksForPairingInstead(@TempDir Path tempDir) {
        PlatformHandle handle = createMockHandle(tempDir, "paper");
        LoaderLifecycle lifecycle = localLifecycle(handle);

        TestSender sender = new TestSender();
        assertThat(lifecycle.handleCommand(sender, new String[]{"update"})).isTrue();
        assertThat(sender.messages).anyMatch(msg -> msg.contains("Server is not paired"));
    }

    @Test
    void updateRerunsTheReleaseCheck(@TempDir Path tempDir) throws Exception {
        AtomicInteger releaseCalls = new AtomicInteger();
        server.createContext("/api/v1/connector/release", exchange -> {
            releaseCalls.incrementAndGet();
            byte[] response = "{\"error\":{\"code\":\"PLAN_REQUIRED\",\"message\":\"Plan required\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(402, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        PlatformHandle handle = createMockHandle(tempDir, "paper");
        ConfigManager config = new ConfigManager(tempDir, handle.logger());
        config.saveCredentials(new LoaderCredentials("mca_live_test", "net-1", "srv-1", "lobby", "paper", Instant.now()));

        LoaderLifecycle lifecycle = localLifecycle(handle);
        lifecycle.onEnable();
        assertThat(releaseCalls.get()).isEqualTo(1);

        TestSender sender = new TestSender();
        assertThat(lifecycle.handleCommand(sender, new String[]{"update"})).isTrue();

        assertThat(releaseCalls.get()).isEqualTo(2);
        assertThat(sender.messages).anyMatch(msg -> msg.contains("Update check finished"));
    }

    @Test
    void downloadedBundleWithABadChecksumIsNeverInstalled(@TempDir Path tempDir) throws Exception {
        byte[] payload = "tampered-bundle-bytes".getBytes(StandardCharsets.UTF_8);
        server.createContext("/api/v1/connector/release", exchange -> {
            String json = "{\"data\":{\"version\":\"3.1.0\",\"sha256\":\"" + "0".repeat(64) + "\",\"sizeBytes\":" + payload.length
                    + ",\"downloadPath\":\"/api/v1/connector/release/download\",\"minLoader\":\"1.0.0\"}}";
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.createContext("/api/v1/connector/release/download", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });

        PlatformHandle handle = createMockHandle(tempDir, "paper");
        ConfigManager config = new ConfigManager(tempDir, handle.logger());
        config.saveCredentials(new LoaderCredentials("mca_live_test", "net-1", "srv-1", "lobby", "paper", Instant.now()));

        LoaderLifecycle lifecycle = localLifecycle(handle);
        lifecycle.onEnable();

        assertThat(lifecycle.getState()).isEqualTo(LoaderLifecycle.State.FAILED);
        assertThat(loggedMessages).anyMatch(msg -> msg.contains("Failed to download release") && msg.contains("Checksum mismatch"));
        assertThat(tempDir.resolve("cache").resolve("connector-paper-3.1.0.jar")).doesNotExist();

        try (var stream = Files.list(tempDir.resolve("cache"))) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void verifiedBundleIsInstalledIntoTheCache(@TempDir Path tempDir) throws Exception {
        byte[] payload = "good-bundle-bytes".getBytes(StandardCharsets.UTF_8);
        String sha = ChecksumUtil.sha256(payload);
        server.createContext("/api/v1/connector/release", exchange -> {
            String json = "{\"data\":{\"version\":\"3.2.0\",\"sha256\":\"" + sha + "\",\"sizeBytes\":" + payload.length
                    + ",\"downloadPath\":\"/api/v1/connector/release/download\",\"minLoader\":\"1.0.0\"}}";
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.createContext("/api/v1/connector/release/download", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });

        PlatformHandle handle = createMockHandle(tempDir, "paper");
        ConfigManager config = new ConfigManager(tempDir, handle.logger());
        config.saveCredentials(new LoaderCredentials("mca_live_test", "net-1", "srv-1", "lobby", "paper", Instant.now()));

        LoaderLifecycle lifecycle = localLifecycle(handle);
        lifecycle.onEnable();

        Path installed = tempDir.resolve("cache").resolve("connector-paper-3.2.0.jar");
        assertThat(installed).exists();
        assertThat(Files.readAllBytes(installed)).isEqualTo(payload);
        assertThat(loggedMessages).anyMatch(msg -> msg.contains("Verified and installed connector bundle v3.2.0"));
        assertThat(lifecycle.getState()).isEqualTo(LoaderLifecycle.State.FAILED);
        assertThat(loggedMessages).anyMatch(msg -> msg.contains("Failed to start connector bundle"));
    }

    @Test
    void loaderOlderThanMinLoaderWarnsButStillProceeds(@TempDir Path tempDir) throws Exception {
        byte[] payload = "newer-bundle".getBytes(StandardCharsets.UTF_8);
        String sha = ChecksumUtil.sha256(payload);
        server.createContext("/api/v1/connector/release", exchange -> {
            String json = "{\"data\":{\"version\":\"4.0.0\",\"sha256\":\"" + sha + "\",\"sizeBytes\":" + payload.length
                    + ",\"downloadPath\":\"/api/v1/connector/release/download\",\"minLoader\":\"9.0.0\"}}";
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.createContext("/api/v1/connector/release/download", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });

        PlatformHandle handle = createMockHandle(tempDir, "paper");
        ConfigManager config = new ConfigManager(tempDir, handle.logger());
        config.saveCredentials(new LoaderCredentials("mca_live_test", "net-1", "srv-1", "lobby", "paper", Instant.now()));

        LoaderLifecycle lifecycle = localLifecycle(handle);
        lifecycle.onEnable();

        assertThat(loggedMessages).anyMatch(msg -> msg.contains("older than required minLoader 9.0.0"));
        assertThat(tempDir.resolve("cache").resolve("connector-paper-4.0.0.jar")).exists();
    }
}
