package net.sniffstudio.mcanalytics.loader.lifecycle;

import net.sniffstudio.mcanalytics.loader.api.CommandDelegate;
import net.sniffstudio.mcanalytics.loader.api.CommandSender;
import net.sniffstudio.mcanalytics.loader.api.ConnectorEntrypoint;
import net.sniffstudio.mcanalytics.loader.api.LoaderLogger;
import net.sniffstudio.mcanalytics.loader.api.PlatformHandle;
import net.sniffstudio.mcanalytics.loader.config.ConfigManager;
import net.sniffstudio.mcanalytics.loader.config.LoaderCredentials;
import net.sniffstudio.mcanalytics.loader.net.ConnectionProblem;
import net.sniffstudio.mcanalytics.loader.net.ReleaseClient;
import net.sniffstudio.mcanalytics.loader.util.VersionUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class LoaderLifecycle {

    public enum State {
        UNPAIRED,
        CHECKING,
        PAUSED_NO_PLAN,
        ACTIVE,
        FAILED
    }

    /** How often the loader tries again when it has no connector and cannot reach the site. */
    static final Duration OFFLINE_RETRY_INTERVAL = Duration.ofMinutes(2);
    /** How often a still failing retry repeats its short reminder. */
    static final Duration OFFLINE_REMINDER_INTERVAL = Duration.ofMinutes(30);

    private final PlatformHandle handle;
    private final ConfigManager configManager;
    private final ReleaseClient releaseClient;
    private final BundleManager bundleManager;
    private final LoaderLogger logger;
    /** Set only by tests. Null means the locked public address. */
    private final String endpointOverride;
    private final Clock clock;

    private final ScheduledExecutorService recheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcanalytics-loader-recheck");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pausedRecheckTask;
    private ScheduledFuture<?> offlineRetryTask;
    /** Clock millis of the last offline line, or -1 while the site is reachable. */
    private volatile long lastOfflineNoticeMillis = -1;

    private final AtomicReference<State> state = new AtomicReference<>(State.UNPAIRED);
    private volatile ConnectorClassLoader classLoader;
    private volatile ConnectorEntrypoint entrypoint;

    public LoaderLifecycle(PlatformHandle handle) {
        this(handle, null, Clock.systemUTC());
    }

    /**
     * For tests only: talks to {@code endpointOverride} instead of the public address. Operators
     * have no way to reach this constructor.
     */
    LoaderLifecycle(PlatformHandle handle, String endpointOverride, Clock clock) {
        this.handle = handle;
        this.logger = handle.logger();
        this.configManager = new ConfigManager(handle.dataDirectory(), handle.logger());
        this.releaseClient = new ReleaseClient(handle.loaderVersion(), handle.platform());
        this.bundleManager = new BundleManager(handle.dataDirectory());
        this.endpointOverride = endpointOverride;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    private String apiUrl() {
        return endpointOverride != null ? endpointOverride : configManager.resolveApiUrl();
    }

    public State getState() {
        return state.get();
    }

    public void onEnable() {
        configManager.logIgnoredAddressSettingOnce();
        Optional<LoaderCredentials> credentials = configManager.readCredentials();
        if (credentials.isEmpty() || !credentials.get().isComplete()) {
            state.set(State.UNPAIRED);
            logger.info("[MCAnalytics] Server is not paired. Run '/mca pair <code>' in console to connect.");
            return;
        }

        handle.asyncExecutor().execute(() -> runReleaseCheckAndLoad(credentials.get()));
    }

    public void onDisable() {
        cancelPausedRecheck();
        cancelOfflineRetry();
        recheckScheduler.shutdownNow();
        stopRunningBundle();
        state.set(State.UNPAIRED);
    }

    private void stopRunningBundle() {
        if (entrypoint != null) {
            try {
                entrypoint.stop();
            } catch (Throwable t) {
                logger.error("[MCAnalytics] Error while stopping connector: " + t.getMessage(), t);
            } finally {
                entrypoint = null;
            }
        }

        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (IOException ignored) {
            } finally {
                classLoader = null;
            }
        }

        handle.setCommandDelegate(null);
    }

    private synchronized void schedulePausedRecheck(LoaderCredentials credentials) {
        if (pausedRecheckTask != null && !pausedRecheckTask.isDone()) {
            return;
        }
        try {
            pausedRecheckTask = recheckScheduler.scheduleWithFixedDelay(() -> {
                if (state.get() == State.PAUSED_NO_PLAN) {
                    logger.info("[MCAnalytics] Re-checking plan status...");
                    handle.asyncExecutor().execute(() -> runReleaseCheckAndLoad(credentials));
                }
            }, 30, 30, TimeUnit.MINUTES);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private synchronized void cancelPausedRecheck() {
        if (pausedRecheckTask != null) {
            pausedRecheckTask.cancel(false);
            pausedRecheckTask = null;
        }
    }

    /**
     * Tries the release check again while the loader has no connector to run because the site
     * could not be reached. Stops on its own once a connector is running.
     */
    private synchronized void scheduleOfflineRetry(LoaderCredentials credentials) {
        if (offlineRetryTask != null && !offlineRetryTask.isDone()) {
            return;
        }
        long minutes = OFFLINE_RETRY_INTERVAL.toMinutes();
        try {
            offlineRetryTask = recheckScheduler.scheduleWithFixedDelay(() -> {
                if (state.get() == State.FAILED) {
                    handle.asyncExecutor().execute(() -> runReleaseCheckAndLoad(credentials));
                }
            }, minutes, minutes, TimeUnit.MINUTES);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private synchronized void cancelOfflineRetry() {
        if (offlineRetryTask != null) {
            offlineRetryTask.cancel(false);
            offlineRetryTask = null;
        }
    }

    synchronized boolean isOfflineRetryScheduled() {
        return offlineRetryTask != null && !offlineRetryTask.isDone();
    }

    /**
     * One calm line for a failed update check. The first one of an outage is a warning when there
     * is no connector to fall back on; while the outage lasts, a short reminder at most every
     * {@link #OFFLINE_REMINDER_INTERVAL}.
     */
    private void noteSiteUnreachable(String reason, Path cachedBundle) {
        long now = clock.millis();
        long last = lastOfflineNoticeMillis;
        boolean firstNotice = last < 0;

        String cannotReach = "[MCAnalytics] Cannot reach " + ConnectionProblem.PUBLIC_HOST + " right now (" + reason + "). ";
        if (cachedBundle != null) {
            // Said every time: it explains which connector is starting, and this happens once per
            // start or /mca update, never in a loop.
            lastOfflineNoticeMillis = now;
            logger.info(cannotReach + "Starting the connector already saved on this server ("
                    + cachedBundle.getFileName() + "). Updates are checked again at the next restart.");
            return;
        }
        if (!firstNotice && now - last < OFFLINE_REMINDER_INTERVAL.toMillis()) {
            return;
        }
        lastOfflineNoticeMillis = now;
        if (firstNotice) {
            logger.warn(cannotReach + "Your server is fine. Analytics starts as soon as the connector can be "
                    + "downloaded, and the loader tries again every " + OFFLINE_RETRY_INTERVAL.toMinutes()
                    + " minutes. If this lasts more than 30 minutes, open a ticket in our Discord: "
                    + ConnectionProblem.DISCORD_URL);
        } else {
            logger.warn("[MCAnalytics] Still cannot reach " + ConnectionProblem.PUBLIC_HOST + " (" + reason
                    + "). Trying again every " + OFFLINE_RETRY_INTERVAL.toMinutes() + " minutes. Discord: "
                    + ConnectionProblem.DISCORD_URL);
        }
    }

    /** Says the site is back, but only when an offline line was printed first. */
    private void noteSiteReachable() {
        if (lastOfflineNoticeMillis >= 0) {
            lastOfflineNoticeMillis = -1;
            logger.info("[MCAnalytics] Connection to " + ConnectionProblem.PUBLIC_HOST + " is back.");
        }
    }

    public void runReleaseCheckAndLoad(LoaderCredentials credentials) {
        state.set(State.CHECKING);
        try {
            String apiUrl = apiUrl();
            String dashboardUrl = configManager.resolveDashboardUrl(apiUrl);

            ReleaseClient.ReleaseCheckResult checkResult = releaseClient.checkRelease(
                    apiUrl,
                    handle.platform(),
                    credentials.connectorToken()
            );

            if (checkResult.statusCode() == 402) {
                noteSiteReachable();
                cancelOfflineRetry();
                logger.info("[MCAnalytics] Network has no active plan. Analytics is paused. Pick a plan at " + dashboardUrl + ".");
                state.set(State.PAUSED_NO_PLAN);
                schedulePausedRecheck(credentials);
                return;
            }

            cancelPausedRecheck();

            if (checkResult.statusCode() == 401) {
                noteSiteReachable();
                cancelOfflineRetry();
                logger.info("[MCAnalytics] Connector token was revoked, so this server is no longer paired. "
                        + "Run /mca pair <code> to pair it again.");
                configManager.clearCredentials();
                state.set(State.UNPAIRED);
                return;
            }

            Path bundleToLoad = null;
            // Plain words for why no fresh bundle is available, when the reason is the connection.
            String unreachableReason = null;

            if (checkResult.statusCode() == 200 && checkResult.metadata() != null) {
                ReleaseClient.ReleaseMetadata meta = checkResult.metadata();

                if (VersionUtil.compare(handle.loaderVersion(), meta.minLoader()) < 0) {
                    logger.warn("[MCAnalytics] WARNING: Loader version " + handle.loaderVersion()
                            + " is older than required minLoader " + meta.minLoader()
                            + ". Please download the updated loader from the dashboard.");
                }

                try {
                    Path targetPath = bundleManager.getBundlePath(handle.platform(), meta.version());
                    if (bundleManager.isBundleValid(targetPath, meta.sha256())) {
                        noteSiteReachable();
                        logger.info("[MCAnalytics] Using cached connector bundle v" + meta.version() + ".");
                        bundleToLoad = targetPath;
                    } else {
                        Path tempFile = Files.createTempFile(bundleManager.getCacheDir(), "connector-dl-", ".tmp");
                        try {
                            String downloadUrl = apiUrl + meta.downloadPath();
                            logger.info("[MCAnalytics] Downloading connector bundle v" + meta.version() + "...");
                            releaseClient.downloadBundle(downloadUrl, credentials.connectorToken(), tempFile, meta.sha256(), meta.sizeBytes());
                            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
                            noteSiteReachable();
                            logger.info("[MCAnalytics] Verified and installed connector bundle v" + meta.version() + ".");
                            bundleToLoad = targetPath;
                        } catch (ReleaseClient.DownloadStatusException e) {
                            Files.deleteIfExists(tempFile);
                            unreachableReason = ConnectionProblem.describeStatus(e.statusCode());
                        } catch (IOException e) {
                            Files.deleteIfExists(tempFile);
                            if (isIntegrityFailure(e)) {
                                // Not a connection problem: the bytes did not match the manifest.
                                logger.warn("[MCAnalytics] Failed to download release: " + e.getMessage());
                            } else {
                                unreachableReason = ConnectionProblem.describe(e);
                            }
                        } catch (Exception e) {
                            Files.deleteIfExists(tempFile);
                            if (e instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                            }
                            unreachableReason = ConnectionProblem.describe(e);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("[MCAnalytics] Error processing connector release: " + e.getMessage());
                }
            } else if (checkResult.statusCode() == 0 || ConnectionProblem.isConnectionStatus(checkResult.statusCode())) {
                unreachableReason = checkResult.statusCode() > 0
                        ? ConnectionProblem.describeStatus(checkResult.statusCode())
                        : (checkResult.errorMessage() != null ? checkResult.errorMessage() : "connection failed");
            } else {
                logger.warn("[MCAnalytics] Release check failed: "
                        + (checkResult.errorMessage() != null ? checkResult.errorMessage() : "HTTP " + checkResult.statusCode()));
            }

            if (bundleToLoad == null) {
                Optional<Path> cached = bundleManager.findNewestValidCachedBundle(handle.platform());
                if (unreachableReason != null) {
                    noteSiteUnreachable(unreachableReason, cached.orElse(null));
                }
                if (cached.isPresent()) {
                    if (unreachableReason == null) {
                        logger.info("[MCAnalytics] Starting the connector already saved on this server ("
                                + cached.get().getFileName() + ").");
                    }
                    bundleToLoad = cached.get();
                } else {
                    state.set(State.FAILED);
                    if (unreachableReason != null) {
                        scheduleOfflineRetry(credentials);
                    } else {
                        // Not a connection problem, so trying again on a timer would only repeat it.
                        cancelOfflineRetry();
                        logger.warn("[MCAnalytics] No verified connector is available, so analytics is off. "
                                + "Run /mca update to try again. If this keeps happening, open a ticket in our Discord: "
                                + ConnectionProblem.DISCORD_URL);
                    }
                    return;
                }
            }

            cancelOfflineRetry();

            try {
                ClassLoader parentClassLoader = handle.getClass().getClassLoader();
                ConnectorClassLoader cl = new ConnectorClassLoader(bundleToLoad, parentClassLoader);
                ConnectorEntrypoint ep = cl.loadEntrypoint(handle.platform());

                ep.start(handle);

                this.classLoader = cl;
                this.entrypoint = ep;
                this.state.set(State.ACTIVE);
                logger.info("[MCAnalytics] Connector bundle started successfully.");
            } catch (Throwable t) {
                logger.error("[MCAnalytics] Failed to start connector bundle: " + t.getMessage(), t);
                state.set(State.FAILED);
            }
        } catch (Throwable t) {
            String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            logger.error("[MCAnalytics] " + message, t);
            state.set(State.FAILED);
        }
    }

    private static boolean isIntegrityFailure(IOException e) {
        String message = e.getMessage();
        return message != null && (message.startsWith("Checksum mismatch") || message.startsWith("Downloaded file size mismatch"));
    }

    public boolean handleCommand(CommandSender sender, String[] args) {
        if (args != null && args.length >= 2 && args[0].equalsIgnoreCase("pair")) {
            if (!sender.isConsole() && !sender.hasPermission("mcanalytics.admin")) {
                sender.sendMessage("[MCAnalytics] You do not have permission to pair this server.");
                return true;
            }

            String code = args[1].trim();
            sender.sendMessage("[MCAnalytics] Pairing with code " + code + "...");

            handle.asyncExecutor().execute(() -> {
                String apiUrl = apiUrl();
                ReleaseClient.PairResult result = releaseClient.pair(apiUrl, code, handle.platform());

                if (result.success() && result.credentials() != null) {
                    try {
                        configManager.saveCredentials(result.credentials());
                        stopRunningBundle();
                        sender.sendMessage("[MCAnalytics] Successfully paired server! Fetching connector bundle...");
                        runReleaseCheckAndLoad(result.credentials());
                    } catch (IOException e) {
                        sender.sendMessage("[MCAnalytics] Failed to save credentials to disk: " + e.getMessage());
                    }
                } else {
                    sender.sendMessage("[MCAnalytics] " + result.errorMessage());
                }
            });

            return true;
        }

        if (args != null && args.length >= 1 && args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        if (args != null && args.length >= 1 && args[0].equalsIgnoreCase("update")) {
            if (!sender.isConsole() && !sender.hasPermission("mcanalytics.admin")) {
                sender.sendMessage("[MCAnalytics] You do not have permission to update this server.");
                return true;
            }

            Optional<LoaderCredentials> stored = configManager.readCredentials();
            if (stored.isEmpty()) {
                sender.sendMessage("[MCAnalytics] Server is not paired. Run '/mca pair <code>' in console to connect.");
                return true;
            }

            sender.sendMessage("[MCAnalytics] Checking for a connector update...");
            handle.asyncExecutor().execute(() -> {
                stopRunningBundle();
                runReleaseCheckAndLoad(stored.get());
                sender.sendMessage("[MCAnalytics] Update check finished. Connector state: " + state.get() + ".");
            });
            return true;
        }

        if (state.get() == State.ACTIVE && handle.getCommandDelegate() != null) {
            return handle.getCommandDelegate().onCommand(sender, args != null ? args : new String[0]);
        }

        if (state.get() == State.UNPAIRED) {
            sender.sendMessage("[MCAnalytics] Server is not paired. Run '/mca pair <code>' in console to connect.");
            return true;
        }

        if (state.get() == State.PAUSED_NO_PLAN) {
            String apiUrl = apiUrl();
            String dashboardUrl = configManager.resolveDashboardUrl(apiUrl);
            sender.sendMessage("[MCAnalytics] Network has no active plan. Analytics is paused. Pick a plan at " + dashboardUrl + ".");
            return true;
        }

        if (state.get() == State.CHECKING) {
            sender.sendMessage("[MCAnalytics] Connector is currently checking for updates or starting up. Please wait.");
            return true;
        }

        sender.sendMessage("[MCAnalytics] Connector is not active. Run '/mca pair <code>' to pair.");
        return true;
    }

    private void sendStatus(CommandSender sender) {
        String apiUrl = apiUrl();
        Optional<LoaderCredentials> stored = configManager.readCredentials();

        sender.sendMessage("[MCAnalytics] Loader " + handle.loaderVersion() + " on " + handle.platform() + ".");
        sender.sendMessage("[MCAnalytics] State: " + state.get() + ".");
        sender.sendMessage("[MCAnalytics] Endpoint: " + apiUrl + ".");

        if (stored.isPresent()) {
            LoaderCredentials creds = stored.get();
            String serverName = creds.serverName() != null && !creds.serverName().isBlank() ? creds.serverName() : creds.serverId();
            sender.sendMessage("[MCAnalytics] Paired as " + serverName + " (network " + creds.networkId() + ").");
        } else {
            sender.sendMessage("[MCAnalytics] Not paired. Run '/mca pair <code>' in console to connect.");
        }

        sender.sendMessage("[MCAnalytics] Bundle: " + loadedBundleName() + ".");
    }

    private String loadedBundleName() {
        ConnectorClassLoader current = classLoader;
        if (current == null) {
            return "none loaded";
        }
        return current.getBundleJar().getFileName().toString();
    }

    void setActiveBundle(ConnectorEntrypoint entrypoint, ConnectorClassLoader classLoader) {
        this.entrypoint = entrypoint;
        this.classLoader = classLoader;
        this.state.set(State.ACTIVE);
    }

    ConnectorEntrypoint getEntrypoint() {
        return entrypoint;
    }

    ConnectorClassLoader getClassLoader() {
        return classLoader;
    }
}
