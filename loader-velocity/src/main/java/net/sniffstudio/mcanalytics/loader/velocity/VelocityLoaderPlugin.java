package net.sniffstudio.mcanalytics.loader.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.sniffstudio.mcanalytics.loader.api.CommandDelegate;
import net.sniffstudio.mcanalytics.loader.api.CommandSender;
import net.sniffstudio.mcanalytics.loader.api.LoaderLogger;
import net.sniffstudio.mcanalytics.loader.api.PlatformHandle;
import net.sniffstudio.mcanalytics.loader.lifecycle.LoaderCommands;
import net.sniffstudio.mcanalytics.loader.lifecycle.LoaderLifecycle;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;

@Plugin(
        id = "mcanalytics-loader",
        name = "MCAnalytics Loader",
        version = VelocityLoaderPlugin.VERSION,
        description = "Downloads, verifies and runs the MCAnalytics connector on a Velocity proxy",
        url = "https://github.com/Jan1k1/mcanalytics-loader",
        authors = {"SniffStudio"}
)
public class VelocityLoaderPlugin implements PlatformHandle {

    public static final String VERSION = "1.0.0";

    private final ProxyServer server;
    private final Path dataDirectory;
    private final LoaderLogger loaderLogger;
    private volatile LoaderLifecycle lifecycle;
    private volatile CommandDelegate commandDelegate;

    @Inject
    public VelocityLoaderPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.dataDirectory = dataDirectory;

        this.loaderLogger = new LoaderLogger() {
            @Override
            public void info(String message) {
                logger.info("{}", message);
            }

            @Override
            public void warn(String message) {
                logger.warn("{}", message);
            }

            @Override
            public void error(String message) {
                logger.error("{}", message);
            }

            @Override
            public void error(String message, Throwable throwable) {
                logger.error("{}", message, throwable);
            }
        };
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        LoaderLifecycle lifecycle = new LoaderLifecycle(this);
        this.lifecycle = lifecycle;

        CommandManager commandManager = server.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("mca")
                .aliases("mcanalytics")
                .plugin(this)
                .build();
        commandManager.register(meta, new VelocityCommandBridge(lifecycle));

        lifecycle.onEnable();
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        LoaderLifecycle current = lifecycle;
        if (current != null) {
            current.onDisable();
        }
    }

    @Override
    public String platform() {
        return "velocity";
    }

    @Override
    public String loaderVersion() {
        return VERSION;
    }

    @Override
    public Path dataDirectory() {
        return dataDirectory;
    }

    @Override
    public LoaderLogger logger() {
        return loaderLogger;
    }

    @Override
    public String serverBrand() {
        return server.getVersion().getName();
    }

    @Override
    public String serverVersion() {
        return server.getVersion().getVersion();
    }

    @Override
    public Executor asyncExecutor() {
        return runnable -> server.getScheduler().buildTask(this, runnable).schedule();
    }

    @Override
    public Object rawServer() {
        return server;
    }

    @Override
    public Object rawPlugin() {
        return this;
    }

    @Override
    public void setCommandDelegate(CommandDelegate delegate) {
        this.commandDelegate = delegate;
    }

    @Override
    public CommandDelegate getCommandDelegate() {
        return commandDelegate;
    }

    private static final class VelocityCommandBridge implements SimpleCommand {
        private final LoaderLifecycle lifecycle;

        VelocityCommandBridge(LoaderLifecycle lifecycle) {
            this.lifecycle = lifecycle;
        }

        @Override
        public void execute(Invocation invocation) {
            lifecycle.handleCommand(wrap(invocation.source()), invocation.arguments());
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            return LoaderCommands.suggest(invocation.arguments());
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("mcanalytics.admin")
                    || !(invocation.source() instanceof Player);
        }

        private static CommandSender wrap(CommandSource source) {
            return new CommandSender() {
                @Override
                public void sendMessage(String message) {
                    source.sendMessage(Component.text(message));
                }

                @Override
                public boolean hasPermission(String permission) {
                    return source.hasPermission(permission);
                }

                @Override
                public boolean isConsole() {
                    return !(source instanceof Player);
                }

                @Override
                public Object rawSource() {
                    return source;
                }
            };
        }
    }
}
