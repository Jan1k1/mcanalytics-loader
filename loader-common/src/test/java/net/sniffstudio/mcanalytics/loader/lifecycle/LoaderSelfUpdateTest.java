package net.sniffstudio.mcanalytics.loader.lifecycle;

import com.sun.net.httpserver.HttpServer;
import net.sniffstudio.mcanalytics.loader.api.CommandDelegate;
import net.sniffstudio.mcanalytics.loader.api.CommandSender;
import net.sniffstudio.mcanalytics.loader.api.LoaderLogger;
import net.sniffstudio.mcanalytics.loader.api.PlatformHandle;
import net.sniffstudio.mcanalytics.loader.config.ConfigManager;
import net.sniffstudio.mcanalytics.loader.config.LoaderCredentials;
import net.sniffstudio.mcanalytics.loader.testsupport.LoaderJars;
import net.sniffstudio.mcanalytics.loader.testsupport.SigningFixture;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The loader looking for a newer loader, end to end against a local server. */
class LoaderSelfUpdateTest {

    private static final String PAPER_JAR = "mcanalytics-loader-paper-1.0.4.jar";
    private static final String VELOCITY_JAR = "mcanalytics-loader-velocity-1.0.4.jar";
    private static final String READY = "MCAnalytics loader 1.0.5 is ready and will be used after the next restart.";

    private final SigningFixture signing = new SigningFixture();
    private final List<String> log = new ArrayList<>();
    private HttpServer server;
    private final AtomicInteger releaseHits = new AtomicInteger();
    private final AtomicInteger downloadHits = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serveLoader(String version, byte[] jar, String signature) {
        server.createContext("/api/v1/connector/loader/release/download", exchange -> {
            downloadHits.incrementAndGet();
            exchange.sendResponseHeaders(200, jar.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(jar);
            }
        });
        server.createContext("/api/v1/connector/loader/release", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/download")) {
                return;
            }
            releaseHits.incrementAndGet();
            String body = "{\"data\":{\"platform\":\"x\",\"version\":\"" + version + "\",\"sha256\":\"" + ChecksumUtil.sha256(jar)
                    + "\",\"sizeBytes\":" + jar.length + ",\"signature\":\"" + signature
                    + "\",\"downloadPath\":\"/api/v1/connector/loader/release/download?platform=x&version=" + version + "\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    /** The connector release route answers 404 so the connector part of every check is quiet. */
    private void serveNoConnector() {
        server.createContext("/api/v1/connector/release", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
    }

    private PlatformHandle handle(Path dataDir, String platform, String version) {
        return new PlatformHandle() {
            private CommandDelegate delegate;

            @Override public String platform() { return platform; }
            @Override public String loaderVersion() { return version; }
            @Override public Path dataDirectory() { return dataDir; }
            @Override public LoaderLogger logger() {
                return new LoaderLogger() {
                    @Override public void info(String message) { log.add("[INFO] " + message); }
                    @Override public void warn(String message) { log.add("[WARN] " + message); }
                    @Override public void error(String message) { log.add("[ERROR] " + message); }
                    @Override public void error(String message, Throwable t) { log.add("[ERROR] " + message); }
                };
            }
            @Override public String serverBrand() { return "Test"; }
            @Override public String serverVersion() { return "1"; }
            @Override public Executor asyncExecutor() { return Runnable::run; }
            @Override public Object rawServer() { return null; }
            @Override public Object rawPlugin() { return null; }
            @Override public void setCommandDelegate(CommandDelegate d) { delegate = d; }
            @Override public CommandDelegate getCommandDelegate() { return delegate; }
        };
    }

    private LoaderLifecycle lifecycle(Path root, String platform, String version, String jarName, boolean withJar) throws IOException {
        Path plugins = Files.createDirectories(root.resolve("plugins"));
        Path jar = plugins.resolve(jarName);
        Files.write(jar, new byte[]{9, 9, 9});
        Path data = Files.createDirectories(plugins.resolve("MCAnalyticsLoader"));
        PlatformHandle handle = handle(data, platform, version);
        new ConfigManager(data, handle.logger()).saveCredentials(
                new LoaderCredentials("mca_live_test", "net-1", "srv-1", "lobby", platform, Instant.now()));
        return new LoaderLifecycle(handle, "http://127.0.0.1:" + server.getAddress().getPort(), Clock.systemUTC(),
                signing.verifier(), () -> withJar ? Optional.of(jar) : Optional.empty());
    }

    private List<String> names(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(folder)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // ---- Staging ------------------------------------------------------------------------

    @Test
    @DisplayName("Paper: a newer signed loader is downloaded, staged as plugins/update/<jar name>, and announced in one line")
    void paperStagesAndAnnounces(@TempDir Path root) throws Exception {
        serveNoConnector();
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        serveLoader("1.0.5", jar, signing.sign(jar));
        LoaderLifecycle lifecycle = lifecycle(root, "paper", "1.0.4", PAPER_JAR, true);

        lifecycle.onEnable();

        Path staged = root.resolve("plugins/update").resolve(PAPER_JAR);
        assertThat(Files.readAllBytes(staged)).isEqualTo(jar);
        assertThat(log.stream().filter(l -> l.contains("is ready and will be used after the next restart"))).containsExactly("[INFO] [MCAnalytics] " + READY);
        assertThat(names(root.resolve("plugins/MCAnalyticsLoader/cache"))).as("no download left in the cache").isEmpty();
        lifecycle.onDisable();
    }

    @Test
    @DisplayName("A second check does not download or announce the same staged loader again")
    void secondCheckIsQuiet(@TempDir Path root) throws Exception {
        serveNoConnector();
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        serveLoader("1.0.5", jar, signing.sign(jar));
        LoaderLifecycle lifecycle = lifecycle(root, "paper", "1.0.4", PAPER_JAR, true);

        assertThat(lifecycle.runLoaderUpdateCheck(false)).isEqualTo(LoaderLifecycle.LoaderCheck.STAGED);
        assertThat(lifecycle.runLoaderUpdateCheck(false)).isEqualTo(LoaderLifecycle.LoaderCheck.STAGED);

        assertThat(downloadHits.get()).isEqualTo(1);
        assertThat(log.stream().filter(l -> l.contains("is ready"))).hasSize(1);
        lifecycle.onDisable();
    }

    @Test
    @DisplayName("A restart that finds the loader already staged does not download it again")
    void staleStagedFileSkipsDownloadAfterRestart(@TempDir Path root) throws Exception {
        serveNoConnector();
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        serveLoader("1.0.5", jar, signing.sign(jar));
        LoaderLifecycle first = lifecycle(root, "paper", "1.0.4", PAPER_JAR, true);
        first.runLoaderUpdateCheck(false);
        first.onDisable();
        log.clear();

        LoaderLifecycle second = lifecycle(root, "paper", "1.0.4", PAPER_JAR, true);
        assertThat(second.runLoaderUpdateCheck(false)).isEqualTo(LoaderLifecycle.LoaderCheck.STAGED);

        assertThat(downloadHits.get()).isEqualTo(1);
        assertThat(log).isEmpty();
        second.onDisable();
    }

    @Test
    @DisplayName("A jar that fails verification is refused: nothing is staged and nothing is announced")
    void refusesAJarThatFailsVerification(@TempDir Path root) throws Exception {
        serveNoConnector();
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        serveLoader("1.0.5", jar, SigningFixture.sign(SigningFixture.newKeyPair(), jar));
        LoaderLifecycle lifecycle = lifecycle(root, "paper", "1.0.4", PAPER_JAR, true);

        assertThat(lifecycle.runLoaderUpdateCheck(false)).isEqualTo(LoaderLifecycle.LoaderCheck.REFUSED);

        assertThat(names(root.resolve("plugins"))).containsExactlyInAnyOrder(PAPER_JAR, "MCAnalyticsLoader");
        assertThat(log.stream().anyMatch(l -> l.contains("is ready"))).isFalse();
        assertThat(log.stream().anyMatch(l -> l.startsWith("[WARN]") && l.contains("signature"))).isTrue();
        assertThat(names(root.resolve("plugins/MCAnalyticsLoader/cache"))).isEmpty();
        lifecycle.onDisable();
    }

    @Test
    @DisplayName("A release that offers the same or an older version is ignored without a download")
    void noDowngrade(@TempDir Path root) throws Exception {
        serveNoConnector();
        byte[] jar = LoaderJars.paper("1.0.3", "old");
        serveLoader("1.0.3", jar, signing.sign(jar));
        LoaderLifecycle lifecycle = lifecycle(root, "paper", "1.0.4", PAPER_JAR, true);

        assertThat(lifecycle.runLoaderUpdateCheck(false)).isEqualTo(LoaderLifecycle.LoaderCheck.UP_TO_DATE);

        assertThat(downloadHits.get()).isZero();
        assertThat(Files.exists(root.resolve("plugins/update"))).isFalse();
        lifecycle.onDisable();
    }

    @Test
    @DisplayName("No published loader (404) means the loader does nothing")
    void nothingPublished(@TempDir Path root) throws Exception {
        serveNoConnector();
        server.createContext("/api/v1/connector/loader/release", exchange -> {
            releaseHits.incrementAndGet();
            byte[] body = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"none\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        LoaderLifecycle lifecycle = lifecycle(root, "paper", "1.0.4", PAPER_JAR, true);

        lifecycle.onEnable();

        assertThat(releaseHits.get()).isEqualTo(1);
        assertThat(log).noneMatch(l -> l.contains("is ready") || l.contains("Not updating") || l.contains("Loader update"));
        assertThat(Files.exists(root.resolve("plugins/update"))).isFalse();
        lifecycle.onDisable();
    }

    // ---- Off switch and safety ----------------------------------------------------------

    @Test
    @DisplayName("auto-update-loader: false stops the check before any request")
    void switchedOff(@TempDir Path root) throws Exception {
        serveNoConnector();
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        serveLoader("1.0.5", jar, signing.sign(jar));
        LoaderLifecycle lifecycle = lifecycle(root, "paper", "1.0.4", PAPER_JAR, true);
        Files.writeString(root.resolve("plugins/MCAnalyticsLoader/config.yml"), "auto-update-loader: false\n");

        lifecycle.onEnable();

        assertThat(lifecycle.runLoaderUpdateCheck(true)).isEqualTo(LoaderLifecycle.LoaderCheck.DISABLED);
        assertThat(releaseHits.get()).isZero();
        assertThat(Files.exists(root.resolve("plugins/update"))).isFalse();
        lifecycle.onDisable();
    }

    @Test
    @DisplayName("Without a locatable running jar nothing is requested or written")
    void noJarNoAction(@TempDir Path root) throws Exception {
        serveNoConnector();
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        serveLoader("1.0.5", jar, signing.sign(jar));
        LoaderLifecycle lifecycle = lifecycle(root, "paper", "1.0.4", PAPER_JAR, false);

        assertThat(lifecycle.runLoaderUpdateCheck(false)).isEqualTo(LoaderLifecycle.LoaderCheck.NO_JAR);

        assertThat(releaseHits.get()).isZero();
        assertThat(Files.exists(root.resolve("plugins/update"))).isFalse();
        assertThat(log).containsExactly("[INFO] [MCAnalytics] Loader auto-update is skipped: the loader jar could not be located.");
        lifecycle.runLoaderUpdateCheck(false);
        assertThat(log).as("said once").hasSize(1);
        lifecycle.onDisable();
    }

    @Test
    @DisplayName("An unpaired server does not contact the loader release endpoint")
    void unpaired(@TempDir Path root) throws Exception {
        serveNoConnector();
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        serveLoader("1.0.5", jar, signing.sign(jar));
        LoaderLifecycle lifecycle = lifecycle(root, "paper", "1.0.4", PAPER_JAR, true);
        new ConfigManager(root.resolve("plugins/MCAnalyticsLoader"), handle(root, "paper", "1.0.4").logger()).clearCredentials();

        lifecycle.onEnable();

        assertThat(releaseHits.get()).isZero();
        lifecycle.onDisable();
    }

    // ---- /mca update --------------------------------------------------------------------

    @Test
    @DisplayName("/mca update also runs the loader check and reports it")
    void updateCommandRunsTheLoaderCheck(@TempDir Path root) throws Exception {
        serveNoConnector();
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        serveLoader("1.0.5", jar, signing.sign(jar));
        LoaderLifecycle lifecycle = lifecycle(root, "paper", "1.0.4", PAPER_JAR, true);
        List<String> replies = new ArrayList<>();
        CommandSender console = new CommandSender() {
            @Override public void sendMessage(String message) { replies.add(message); }
            @Override public boolean hasPermission(String permission) { return true; }
            @Override public boolean isConsole() { return true; }
            @Override public Object rawSource() { return null; }
        };

        lifecycle.handleCommand(console, new String[]{"update"});

        assertThat(Files.isRegularFile(root.resolve("plugins/update").resolve(PAPER_JAR))).isTrue();
        assertThat(replies).anyMatch(r -> r.contains("A newer loader is staged and will be used after the next restart"));
        lifecycle.onDisable();
    }

    // ---- Velocity -----------------------------------------------------------------------

    @Test
    @DisplayName("Velocity: the check leaves a .pending file, and shutdown swaps it in so exactly one loader jar remains")
    void velocityPendingThenSwapOnShutdown(@TempDir Path root) throws Exception {
        serveNoConnector();
        byte[] jar = LoaderJars.velocity("1.0.5", "new");
        serveLoader("1.0.5", jar, signing.sign(jar));
        LoaderLifecycle lifecycle = lifecycle(root, "velocity", "1.0.4", VELOCITY_JAR, true);
        Path plugins = root.resolve("plugins");

        lifecycle.onEnable();

        assertThat(names(plugins)).containsExactlyInAnyOrder(VELOCITY_JAR, VELOCITY_JAR + ".pending",
                VELOCITY_JAR + ".pending.verify.json", "MCAnalyticsLoader");
        assertThat(log).anyMatch(l -> l.contains(READY));

        lifecycle.onDisable();

        assertThat(names(plugins)).containsExactlyInAnyOrder(VELOCITY_JAR, "MCAnalyticsLoader");
        assertThat(Files.readAllBytes(plugins.resolve(VELOCITY_JAR))).isEqualTo(jar);
    }

    @Test
    @DisplayName("Velocity: a pending jar left by a crash is swapped in at the next start")
    void velocityPendingSwappedAtNextStart(@TempDir Path root) throws Exception {
        serveNoConnector();
        byte[] jar = LoaderJars.velocity("1.0.5", "new");
        serveLoader("1.0.5", jar, signing.sign(jar));
        LoaderLifecycle first = lifecycle(root, "velocity", "1.0.4", VELOCITY_JAR, true);
        first.runLoaderUpdateCheck(false);
        // No onDisable: the proxy was killed.

        LoaderLifecycle second = lifecycle(root, "velocity", "1.0.4", VELOCITY_JAR, true);
        second.onEnable();

        assertThat(Files.readAllBytes(root.resolve("plugins").resolve(VELOCITY_JAR))).isEqualTo(jar);
        assertThat(names(root.resolve("plugins"))).containsExactlyInAnyOrder(VELOCITY_JAR, "MCAnalyticsLoader");
        second.onDisable();
    }

    @Test
    @DisplayName("Velocity: turning the switch off deletes a pending jar instead of installing it")
    void velocitySwitchOffDiscardsPending(@TempDir Path root) throws Exception {
        serveNoConnector();
        byte[] jar = LoaderJars.velocity("1.0.5", "new");
        serveLoader("1.0.5", jar, signing.sign(jar));
        LoaderLifecycle first = lifecycle(root, "velocity", "1.0.4", VELOCITY_JAR, true);
        first.runLoaderUpdateCheck(false);
        Files.writeString(root.resolve("plugins/MCAnalyticsLoader/config.toml"), "auto_update_loader = false\n");

        first.onDisable();

        assertThat(names(root.resolve("plugins"))).containsExactlyInAnyOrder(VELOCITY_JAR, "MCAnalyticsLoader");
        assertThat(Files.readAllBytes(root.resolve("plugins").resolve(VELOCITY_JAR))).isEqualTo(new byte[]{9, 9, 9});
    }

    // ---- Schedule -----------------------------------------------------------------------

    @Test
    @DisplayName("The next check comes about six hours later, with ten percent of jitter either way")
    void checkDelayIsSixHoursWithJitter() {
        Duration six = Duration.ofHours(6);
        assertThat(LoaderLifecycle.loaderCheckDelay(0.5)).isEqualTo(six);
        assertThat(LoaderLifecycle.loaderCheckDelay(0.0)).isEqualTo(Duration.ofMinutes(324));
        assertThat(LoaderLifecycle.loaderCheckDelay(1.0)).isEqualTo(Duration.ofMinutes(396));
        assertThat(LoaderLifecycle.loaderCheckDelay(-5)).isEqualTo(Duration.ofMinutes(324));
        assertThat(LoaderLifecycle.loaderCheckDelay(9)).isEqualTo(Duration.ofMinutes(396));
    }
}
