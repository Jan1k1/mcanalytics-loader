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
import net.sniffstudio.mcanalytics.loader.update.LoaderJarLocator;
import net.sniffstudio.mcanalytics.loader.update.LoaderUpdater;
import net.sniffstudio.mcanalytics.loader.util.BundleVerifier;
import net.sniffstudio.mcanalytics.loader.util.VersionUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

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
    /** How often a refused or failed update is tried again when the cause is not the connection. */
    static final Duration UPDATE_RETRY_INTERVAL = Duration.ofMinutes(30);

    /** How often the loader looks for a newer loader jar, before jitter. */
    static final Duration LOADER_CHECK_INTERVAL = Duration.ofHours(6);
    /** The jitter around {@link #LOADER_CHECK_INTERVAL}: plus or minus this share of it. */
    static final double LOADER_CHECK_JITTER = 0.10;

    /** What one loader self-update check ended with. */
    public enum LoaderCheck {
        /** {@code auto-update-loader} is false. */
        DISABLED,
        /** The running loader jar could not be located, so nothing is touched. */
        NO_JAR,
        /** The server publishes no loader, or could not be asked. */
        NOTHING_PUBLISHED,
        UP_TO_DATE,
        /** A newer verified jar is staged for the next restart. */
        STAGED,
        /** The download or the staged jar was refused. */
        REFUSED,
        /** Another check was already running. */
        BUSY,
        /** The check could not finish (connection, unpaired). */
        FAILED
    }

    private final PlatformHandle handle;
    private final ConfigManager configManager;
    private final ReleaseClient releaseClient;
    private final BundleManager bundleManager;
    private final LoaderLogger logger;
    /** Set only by tests. Null means the locked public address. */
    private final String endpointOverride;
    private final Clock clock;
    private final BundleVerifier verifier;
    /** Finds the running loader jar. Tests replace it, because a test class is not in a jar. */
    private final Supplier<Optional<Path>> jarLocator;
    /** The locator of the tests that do not exercise self-update: finds no jar and says nothing about it. */
    private static final Supplier<Optional<Path>> NO_JAR_QUIET = Optional::empty;
    private final Random jitter = new Random();
    /** Serializes loader self-update checks; separate from the connector lock so neither waits on the other. */
    private final ReentrantLock loaderUpdateLock = new ReentrantLock();
    private boolean loaderChecksStarted;
    private volatile boolean jarNoticeLogged;
    /** The highest loader version already staged during this run, so a check does not download it again. */
    private volatile String stagedLoaderVersion;

    private final ScheduledExecutorService recheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcanalytics-loader-recheck");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pausedRecheckTask;
    private ScheduledFuture<?> offlineRetryTask;
    private Duration offlineRetryTaskInterval;
    /**
     * Held for the whole of a release check, start, stop or update, so two of them can never
     * interleave and run two connectors.
     */
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private volatile boolean disabled;
    /** True while a connector runs but the last attempt to check for a newer one failed. */
    private volatile boolean updateRetryPending;
    /** Clock millis of the last offline line, or -1 while the site is reachable. */
    private volatile long lastOfflineNoticeMillis = -1;

    private final AtomicReference<State> state = new AtomicReference<>(State.UNPAIRED);
    private volatile ConnectorClassLoader classLoader;
    private volatile ConnectorEntrypoint entrypoint;

    public LoaderLifecycle(PlatformHandle handle) {
        this(handle, null, Clock.systemUTC(), BundleVerifier.production(), null);
    }

    /**
     * For tests only: talks to {@code endpointOverride} instead of the public address. Operators
     * have no way to reach this constructor.
     */
    LoaderLifecycle(PlatformHandle handle, String endpointOverride, Clock clock) {
        this(handle, endpointOverride, clock, BundleVerifier.production());
    }

    /**
     * For tests only: as above, and {@code verifier} decides which signing keys are trusted, so a
     * test can sign with a key it generated. Operators have no way to reach this constructor.
     */
    LoaderLifecycle(PlatformHandle handle, String endpointOverride, Clock clock, BundleVerifier verifier) {
        this(handle, endpointOverride, clock, verifier, NO_JAR_QUIET);
    }

    /**
     * For tests only: as above, and {@code jarLocator} names the running loader jar. Null means
     * the jar {@code handle}'s class was loaded from.
     */
    LoaderLifecycle(PlatformHandle handle, String endpointOverride, Clock clock, BundleVerifier verifier,
                    Supplier<Optional<Path>> jarLocator) {
        this.handle = handle;
        this.verifier = verifier;
        this.jarLocator = jarLocator != null ? jarLocator : () -> LoaderJarLocator.locate(handle.getClass());
        this.logger = handle.logger();
        this.configManager = new ConfigManager(handle.dataDirectory(), handle.logger());
        this.releaseClient = new ReleaseClient(handle.loaderVersion(), handle.platform());
        this.bundleManager = new BundleManager(handle.dataDirectory(), verifier, handle.logger());
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
        disabled = false;
        configManager.logIgnoredAddressSettingOnce();
        configManager.removeStaleCredentialCopies();
        finishPendingLoaderUpdate("start");
        Optional<LoaderCredentials> credentials = configManager.readCredentials();
        if (credentials.isEmpty() || !credentials.get().isComplete()) {
            state.set(State.UNPAIRED);
            logger.info("[MCAnalytics] Server is not paired. Run '" + CONSOLE_PAIR_COMMAND + "' in the console to connect.");
            return;
        }

        handle.asyncExecutor().execute(() -> runReleaseCheckAndLoad(credentials.get()));
        startLoaderUpdateChecks();
    }

    public void onDisable() {
        disabled = true;
        cancelPausedRecheck();
        cancelOfflineRetry();
        recheckScheduler.shutdownNow();
        // Wait a little for a check that is running, then stop whatever is running regardless. A
        // check that finishes later sees the disabled flag and starts nothing.
        boolean locked = false;
        try {
            locked = lifecycleLock.tryLock(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            stopRunningBundle();
            state.set(State.UNPAIRED);
        } finally {
            if (locked) {
                lifecycleLock.unlock();
            }
        }
        finishPendingLoaderUpdate("shutdown");
    }

    // ---- Loader self-update -------------------------------------------------------------

    private Optional<LoaderUpdater> loaderUpdater() {
        Optional<Path> jar;
        try {
            jar = jarLocator.get();
        } catch (RuntimeException e) {
            jar = Optional.empty();
        }
        return jar.map(path -> new LoaderUpdater(handle.platform(), handle.loaderVersion(), path,
                handle.updateFolder(), verifier));
    }

    /**
     * Velocity and BungeeCord, which have no update folder. Swaps in a loader jar that an earlier check left as {@code .pending}, or
     * deletes it when the operator has since turned self-update off. Runs at shutdown and again
     * at start, in case the shutdown never got to run.
     */
    private void finishPendingLoaderUpdate(String when) {
        if (!"velocity".equals(handle.platform()) && !"bungee".equals(handle.platform())) {
            return;
        }
        try {
            Optional<LoaderUpdater> updater = loaderUpdater();
            if (updater.isEmpty()) {
                return;
            }
            if (!configManager.isAutoUpdateLoaderEnabled()) {
                if (updater.get().discardPending()) {
                    logger.info("[MCAnalytics] Loader auto-update is off, so the pending loader update was deleted.");
                }
                return;
            }
            LoaderUpdater.ApplyResult result = updater.get().applyPending();
            switch (result.outcome()) {
                case APPLIED -> logger.info("[MCAnalytics] MCAnalytics loader " + result.detail()
                        + " was installed over " + updater.get().runningJar().getFileName()
                        + " and will be used after the next restart.");
                case DISCARDED -> logger.warn("[MCAnalytics] Deleted a pending loader update: " + result.detail() + ".");
                case FAILED -> logger.warn("[MCAnalytics] Could not install the pending loader update at " + when
                        + ": " + result.detail() + ".");
                default -> { }
            }
        } catch (RuntimeException e) {
            logger.warn("[MCAnalytics] Could not finish the pending loader update: " + e.getClass().getSimpleName() + ".");
        }
    }

    /** The wait before the next loader check: six hours, plus or minus ten percent. */
    static Duration loaderCheckDelay(double randomUnit) {
        double factor = 1.0 - LOADER_CHECK_JITTER + 2 * LOADER_CHECK_JITTER * Math.min(1.0, Math.max(0.0, randomUnit));
        return Duration.ofMillis(Math.round(LOADER_CHECK_INTERVAL.toMillis() * factor));
    }

    /** Checks for a newer loader now and then every six hours with jitter. Idempotent. */
    private void startLoaderUpdateChecks() {
        synchronized (this) {
            if (loaderChecksStarted || disabled) {
                return;
            }
            loaderChecksStarted = true;
        }
        handle.asyncExecutor().execute(() -> runLoaderUpdateCheck(false));
        scheduleNextLoaderCheck();
    }

    private void scheduleNextLoaderCheck() {
        Duration delay = loaderCheckDelay(jitter.nextDouble());
        try {
            recheckScheduler.schedule(() -> {
                if (disabled) {
                    return;
                }
                handle.asyncExecutor().execute(() -> runLoaderUpdateCheck(false));
                scheduleNextLoaderCheck();
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    /**
     * Looks for a newer loader jar and stages it for the next restart when it verifies. It never
     * touches the running connector or the running loader. Safe to call from any thread.
     *
     * @param manual true for {@code /mca update}: a check that finds nothing says so in the log
     */
    LoaderCheck runLoaderUpdateCheck(boolean manual) {
        if (disabled) {
            return LoaderCheck.FAILED;
        }
        if (!configManager.isAutoUpdateLoaderEnabled()) {
            return LoaderCheck.DISABLED;
        }
        Optional<LoaderUpdater> located = loaderUpdater();
        if (located.isEmpty()) {
            if (!jarNoticeLogged && jarLocator != NO_JAR_QUIET) {
                jarNoticeLogged = true;
                logger.info("[MCAnalytics] Loader auto-update is skipped: the loader jar could not be located.");
            }
            return LoaderCheck.NO_JAR;
        }
        Optional<LoaderCredentials> stored = configManager.readCredentials();
        if (stored.isEmpty() || !stored.get().isComplete()) {
            return LoaderCheck.FAILED;
        }
        if (!loaderUpdateLock.tryLock()) {
            return LoaderCheck.BUSY;
        }
        try {
            return checkAndStageLoader(located.get(), stored.get(), manual);
        } catch (Throwable t) {
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("[MCAnalytics] Loader update check failed: " + ConnectionProblem.describe(t) + ".");
            return LoaderCheck.FAILED;
        } finally {
            loaderUpdateLock.unlock();
        }
    }

    private LoaderCheck checkAndStageLoader(LoaderUpdater updater, LoaderCredentials credentials, boolean manual) throws Exception {
        String apiUrl = apiUrl();
        ReleaseClient.ReleaseCheckResult result = releaseClient.checkLoaderRelease(apiUrl, handle.platform(), credentials.connectorToken());
        if (result.statusCode() == 404) {
            return LoaderCheck.NOTHING_PUBLISHED;
        }
        if (result.statusCode() != 200 || result.metadata() == null) {
            if (manual) {
                logger.info("[MCAnalytics] Could not check for a newer loader ("
                        + (result.errorMessage() != null ? result.errorMessage() : "HTTP " + result.statusCode()) + ").");
            }
            return result.statusCode() == ReleaseClient.STATUS_REFUSED ? LoaderCheck.REFUSED : LoaderCheck.FAILED;
        }
        ReleaseClient.ReleaseMetadata meta = result.metadata();
        if (!VersionUtil.isNewer(handle.loaderVersion(), meta.version())) {
            return LoaderCheck.UP_TO_DATE;
        }
        String alreadyStaged = stagedLoaderVersion;
        if (alreadyStaged != null && !VersionUtil.isNewer(alreadyStaged, meta.version())) {
            return LoaderCheck.STAGED;
        }
        Optional<String> onDisk = updater.stagedVersion();
        if (onDisk.isPresent() && !VersionUtil.isNewer(onDisk.get(), meta.version())) {
            stagedLoaderVersion = onDisk.get();
            return LoaderCheck.STAGED;
        }
        if (!ConfigManager.isTrustedApiBase(apiUrl)) {
            return LoaderCheck.REFUSED;
        }

        Path temp = null;
        try {
            // Throws for a path that would move the request to another host.
            String downloadUrl = ReleaseClient.resolveDownloadUri(apiUrl, meta.downloadPath()).toString();
            Files.createDirectories(bundleManager.getCacheDir());
            temp = Files.createTempFile(bundleManager.getCacheDir(), "loader-dl-", ".tmp");
            releaseClient.downloadBundle(downloadUrl, credentials.connectorToken(), temp, meta.sha256(), meta.sizeBytes());
            LoaderUpdater.StageResult staged = updater.stage(temp, meta);
            switch (staged.outcome()) {
                case STAGED -> {
                    stagedLoaderVersion = meta.version();
                    logger.info("[MCAnalytics] MCAnalytics loader " + meta.version()
                            + " is ready and will be used after the next restart.");
                    return LoaderCheck.STAGED;
                }
                case ALREADY_STAGED -> {
                    stagedLoaderVersion = meta.version();
                    return LoaderCheck.STAGED;
                }
                case NOT_NEWER -> {
                    return LoaderCheck.UP_TO_DATE;
                }
                case REFUSED -> {
                    logger.warn("[MCAnalytics] Not updating the loader to " + meta.version() + ": " + staged.detail() + ".");
                    return LoaderCheck.REFUSED;
                }
                default -> {
                    logger.warn("[MCAnalytics] Could not stage loader " + meta.version() + ": " + staged.detail() + ".");
                    return LoaderCheck.FAILED;
                }
            }
        } catch (ReleaseClient.ReleaseRejectedException e) {
            logger.warn("[MCAnalytics] Not updating the loader to " + meta.version() + ": " + e.getMessage() + ".");
            return LoaderCheck.REFUSED;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
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
                    handle.asyncExecutor().execute(() -> runReleaseCheckIfIdle(credentials));
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
        scheduleRetry(credentials, OFFLINE_RETRY_INTERVAL);
    }

    /**
     * Runs the release check again every {@code interval} while there is no connector to run
     * (state FAILED) or a running connector could not be checked for a newer one. A pending
     * retry with another interval is replaced.
     */
    private synchronized void scheduleRetry(LoaderCredentials credentials, Duration interval) {
        if (offlineRetryTask != null && !offlineRetryTask.isDone()) {
            if (interval.equals(offlineRetryTaskInterval)) {
                return;
            }
            offlineRetryTask.cancel(false);
        }
        long minutes = interval.toMinutes();
        try {
            offlineRetryTask = recheckScheduler.scheduleWithFixedDelay(() -> {
                if (state.get() == State.FAILED || updateRetryPending) {
                    handle.asyncExecutor().execute(() -> runReleaseCheckIfIdle(credentials));
                }
            }, minutes, minutes, TimeUnit.MINUTES);
            offlineRetryTaskInterval = interval;
        } catch (RejectedExecutionException ignored) {
        }
    }

    private synchronized void cancelOfflineRetry() {
        updateRetryPending = false;
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

    /** Runs the release check and starts what it finds, waiting for any check already running. */
    public void runReleaseCheckAndLoad(LoaderCredentials credentials) {
        lifecycleLock.lock();
        try {
            runReleaseCheckLocked(credentials, false);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * As {@link #runReleaseCheckAndLoad}, but does nothing when a check, start or stop is already
     * running: the next scheduled run tries again.
     *
     * @return false when it did nothing because the lifecycle was busy
     */
    boolean runReleaseCheckIfIdle(LoaderCredentials credentials) {
        if (!lifecycleLock.tryLock()) {
            return false;
        }
        try {
            runReleaseCheckLocked(credentials, false);
            return true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * The release check. The caller holds {@link #lifecycleLock}. A running connector is only
     * stopped once a verified replacement is ready to start, so a reply that is refused, a site
     * that is down or a failed download never turns a running connector off.
     *
     * @param restartIfSame restart the connector even when the newest version is the one running
     */
    private void runReleaseCheckLocked(LoaderCredentials credentials, boolean restartIfSame) {
        boolean connectorRunning = entrypoint != null;
        if (!connectorRunning) {
            state.set(State.CHECKING);
        }
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
                stopRunningBundle();
                state.set(State.PAUSED_NO_PLAN);
                schedulePausedRecheck(credentials);
                return;
            }

            cancelPausedRecheck();

            if (checkResult.statusCode() == 401) {
                noteSiteReachable();
                cancelOfflineRetry();
                logger.info("[MCAnalytics] Connector token was revoked, so this server is no longer paired. "
                        + "Run '" + CONSOLE_PAIR_COMMAND + "' in the console to pair it again.");
                configManager.clearCredentials();
                stopRunningBundle();
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
                    if (bundleManager.matchesRelease(targetPath, meta)) {
                        noteSiteReachable();
                        logger.info("[MCAnalytics] Using cached connector bundle v" + meta.version() + ".");
                        bundleToLoad = targetPath;
                    } else {
                        Path tempFile = Files.createTempFile(bundleManager.getCacheDir(), "connector-dl-", ".tmp");
                        try {
                            if (!ConfigManager.isTrustedApiBase(apiUrl)) {
                                throw new ReleaseClient.ReleaseRejectedException("Refused to send the token to an untrusted address");
                            }
                            // Throws for a path that would move the request to another host.
                            String downloadUrl = ReleaseClient.resolveDownloadUri(apiUrl, meta.downloadPath()).toString();
                            logger.info("[MCAnalytics] Downloading connector bundle v" + meta.version() + "...");
                            releaseClient.downloadBundle(downloadUrl, credentials.connectorToken(), tempFile, meta.sha256(), meta.sizeBytes());
                            // Checks the sha256 and the signature, then moves the jar into the cache.
                            targetPath = bundleManager.install(tempFile, meta);
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

            if (bundleToLoad == null && connectorRunning) {
                // Nothing newer could be verified. The running connector stays on, and the check
                // is repeated later; a refused reply must not cost the server its analytics.
                if (unreachableReason != null) {
                    logger.info("[MCAnalytics] Cannot reach " + ConnectionProblem.PUBLIC_HOST + " right now ("
                            + unreachableReason + "). The connector that is running stays on.");
                } else {
                    logger.warn("[MCAnalytics] No newer verified connector is available. The connector that is running stays on.");
                }
                updateRetryPending = true;
                scheduleRetry(credentials, unreachableReason != null ? OFFLINE_RETRY_INTERVAL : UPDATE_RETRY_INTERVAL);
                return;
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
                        // A refused reply may be fixed on the server side, so try again later
                        // instead of staying off until someone restarts.
                        scheduleRetry(credentials, UPDATE_RETRY_INTERVAL);
                        logger.warn("[MCAnalytics] No verified connector is available, so analytics is off. "
                                + "The loader tries again every " + UPDATE_RETRY_INTERVAL.toMinutes()
                                + " minutes, or run /mca update to try now. If this keeps happening, open a ticket in our Discord: "
                                + ConnectionProblem.DISCORD_URL);
                    }
                    return;
                }
            }

            cancelOfflineRetry();

            // Every load is verified again, including a fresh install and a fallback.
            String verificationFailure = bundleManager.verificationFailure(bundleToLoad);
            if (verificationFailure != null) {
                logger.warn("[MCAnalytics] Not starting " + bundleToLoad.getFileName() + ": " + verificationFailure + ".");
                if (connectorRunning) {
                    updateRetryPending = true;
                    scheduleRetry(credentials, UPDATE_RETRY_INTERVAL);
                } else {
                    state.set(State.FAILED);
                }
                return;
            }

            if (disabled) {
                return;
            }

            ConnectorClassLoader running = classLoader;
            if (connectorRunning && running != null && !restartIfSame && running.getBundleJar().toAbsolutePath().normalize()
                    .equals(bundleToLoad.toAbsolutePath().normalize())) {
                // Already running the newest verified version.
                state.set(State.ACTIVE);
                return;
            }
            if (connectorRunning) {
                state.set(State.CHECKING);
                stopRunningBundle();
            }

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
            if (entrypoint == null) {
                state.set(State.FAILED);
            }
        }
    }

    private static boolean isIntegrityFailure(IOException e) {
        return e instanceof ReleaseClient.ReleaseRejectedException;
    }

    /**
     * The pair command as typed in a server console. The Velocity console does not take a leading
     * slash, and the bare command works in every console, so this is the form a log line names.
     */
    static final String CONSOLE_PAIR_COMMAND = "mca pair <code>";

    /** The pair command as the sender types it: bare in a console, with a slash in chat. */
    static String pairCommand(CommandSender sender) {
        return sender == null || sender.isConsole() ? CONSOLE_PAIR_COMMAND : "/" + CONSOLE_PAIR_COMMAND;
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
                    lifecycleLock.lock();
                    try {
                        configManager.saveCredentials(result.credentials());
                        stopRunningBundle();
                        sender.sendMessage("[MCAnalytics] Successfully paired server! Fetching connector bundle...");
                        runReleaseCheckLocked(result.credentials(), false);
                    } catch (IOException e) {
                        sender.sendMessage("[MCAnalytics] Failed to save credentials to disk: " + e.getMessage());
                    } finally {
                        lifecycleLock.unlock();
                    }
                    startLoaderUpdateChecks();
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
                sender.sendMessage("[MCAnalytics] Server is not paired. Run '" + pairCommand(sender) + "' to connect.");
                return true;
            }

            sender.sendMessage("[MCAnalytics] Checking for a connector update...");
            handle.asyncExecutor().execute(() -> {
                if (!lifecycleLock.tryLock()) {
                    sender.sendMessage("[MCAnalytics] An update or start is already in progress, so this request was ignored.");
                    return;
                }
                try {
                    // The running connector is replaced only after a verified new one is ready.
                    runReleaseCheckLocked(stored.get(), true);
                } finally {
                    lifecycleLock.unlock();
                }
                sender.sendMessage("[MCAnalytics] Update check finished. Connector state: " + state.get() + ".");
                sender.sendMessage("[MCAnalytics] " + describeLoaderCheck(runLoaderUpdateCheck(true)));
            });
            return true;
        }

        if (state.get() == State.ACTIVE && handle.getCommandDelegate() != null) {
            return handle.getCommandDelegate().onCommand(sender, args != null ? args : new String[0]);
        }

        if (state.get() == State.UNPAIRED) {
            sender.sendMessage("[MCAnalytics] Server is not paired. Run '" + pairCommand(sender) + "' to connect.");
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

        sender.sendMessage("[MCAnalytics] Connector is not active. Run '" + pairCommand(sender) + "' to pair.");
        return true;
    }

    private String describeLoaderCheck(LoaderCheck check) {
        return switch (check) {
            case DISABLED -> "Loader auto-update is off (auto-update-loader: false), so the loader was not checked.";
            case NO_JAR -> "The loader jar could not be located, so the loader was not checked.";
            case NOTHING_PUBLISHED -> "No newer loader is published. Loader " + handle.loaderVersion() + " stays.";
            case UP_TO_DATE -> "Loader " + handle.loaderVersion() + " is up to date.";
            case STAGED -> "A newer loader is staged and will be used after the next restart.";
            case REFUSED -> "A newer loader was refused because it did not verify. Loader " + handle.loaderVersion() + " stays.";
            case BUSY -> "A loader check is already running.";
            case FAILED -> "The loader check could not finish. Loader " + handle.loaderVersion() + " stays.";
        };
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
            sender.sendMessage("[MCAnalytics] Not paired. Run '" + pairCommand(sender) + "' to connect.");
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
