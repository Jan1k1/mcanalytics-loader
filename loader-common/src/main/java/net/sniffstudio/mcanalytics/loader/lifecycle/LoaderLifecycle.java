package net.sniffstudio.mcanalytics.loader.lifecycle;

import net.sniffstudio.mcanalytics.loader.api.CommandDelegate;
import net.sniffstudio.mcanalytics.loader.api.CommandSender;
import net.sniffstudio.mcanalytics.loader.api.ConnectorEntrypoint;
import net.sniffstudio.mcanalytics.loader.api.LoaderLogger;
import net.sniffstudio.mcanalytics.loader.api.PlatformHandle;
import net.sniffstudio.mcanalytics.loader.config.ConfigManager;
import net.sniffstudio.mcanalytics.loader.config.LoaderCredentials;
import net.sniffstudio.mcanalytics.loader.net.ReleaseClient;
import net.sniffstudio.mcanalytics.loader.util.VersionUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    private final PlatformHandle handle;
    private final ConfigManager configManager;
    private final ReleaseClient releaseClient;
    private final BundleManager bundleManager;
    private final LoaderLogger logger;

    private final ScheduledExecutorService recheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mcanalytics-loader-recheck");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pausedRecheckTask;

    private final AtomicReference<State> state = new AtomicReference<>(State.UNPAIRED);
    private volatile ConnectorClassLoader classLoader;
    private volatile ConnectorEntrypoint entrypoint;

    public LoaderLifecycle(PlatformHandle handle) {
        this.handle = handle;
        this.logger = handle.logger();
        this.configManager = new ConfigManager(handle.dataDirectory(), handle.logger());
        this.releaseClient = new ReleaseClient(handle.loaderVersion(), handle.platform());
        this.bundleManager = new BundleManager(handle.dataDirectory());
    }

    public State getState() {
        return state.get();
    }

    public void onEnable() {
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

    public void runReleaseCheckAndLoad(LoaderCredentials credentials) {
        state.set(State.CHECKING);
        try {
            String apiUrl = configManager.resolveApiUrl();
            String dashboardUrl = configManager.resolveDashboardUrl(apiUrl);

            ReleaseClient.ReleaseCheckResult checkResult = releaseClient.checkRelease(
                    apiUrl,
                    handle.platform(),
                    credentials.connectorToken()
            );

            if (checkResult.statusCode() == 402) {
                logger.info("[MCAnalytics] Network has no active plan. Analytics is paused. Pick a plan at " + dashboardUrl + ".");
                state.set(State.PAUSED_NO_PLAN);
                schedulePausedRecheck(credentials);
                return;
            }

            cancelPausedRecheck();

            if (checkResult.statusCode() == 401) {
                logger.info("[MCAnalytics] Connector token was revoked. Run /mca pair <code> to re-pair.");
                configManager.clearCredentials();
                state.set(State.UNPAIRED);
                return;
            }

            Path bundleToLoad = null;

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
                        logger.info("[MCAnalytics] Using cached connector bundle v" + meta.version() + ".");
                        bundleToLoad = targetPath;
                    } else {
                        Path tempFile = Files.createTempFile(bundleManager.getCacheDir(), "connector-dl-", ".tmp");
                        try {
                            String downloadUrl = apiUrl + meta.downloadPath();
                            logger.info("[MCAnalytics] Downloading connector bundle v" + meta.version() + "...");
                            releaseClient.downloadBundle(downloadUrl, credentials.connectorToken(), tempFile, meta.sha256(), meta.sizeBytes());
                            Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
                            logger.info("[MCAnalytics] Verified and installed connector bundle v" + meta.version() + ".");
                            bundleToLoad = targetPath;
                        } catch (Exception e) {
                            Files.deleteIfExists(tempFile);
                            logger.warn("[MCAnalytics] Failed to download release: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("[MCAnalytics] Error processing connector release: " + e.getMessage());
                }
            }

            if (bundleToLoad == null) {
                Optional<Path> cached = bundleManager.findNewestValidCachedBundle(handle.platform());
                if (cached.isPresent()) {
                    logger.warn("[MCAnalytics] Using cached fallback bundle: " + cached.get().getFileName());
                    bundleToLoad = cached.get();
                } else {
                    logger.error("[MCAnalytics] No valid connector bundle available. Analytics is offline.");
                    state.set(State.FAILED);
                    return;
                }
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
            state.set(State.FAILED);
        }
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
                String apiUrl = configManager.resolveApiUrl();
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
            String apiUrl = configManager.resolveApiUrl();
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
        String apiUrl = configManager.resolveApiUrl();
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
