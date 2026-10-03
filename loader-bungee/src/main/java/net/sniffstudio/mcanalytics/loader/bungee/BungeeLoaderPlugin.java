package net.sniffstudio.mcanalytics.loader.bungee;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.TabExecutor;
import net.sniffstudio.mcanalytics.loader.api.CommandDelegate;
import net.sniffstudio.mcanalytics.loader.api.CommandSender;
import net.sniffstudio.mcanalytics.loader.api.LoaderLogger;
import net.sniffstudio.mcanalytics.loader.api.PlatformHandle;
import net.sniffstudio.mcanalytics.loader.lifecycle.LoaderCommands;
import net.sniffstudio.mcanalytics.loader.lifecycle.LoaderLifecycle;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.logging.Level;

/**
 * The loader on BungeeCord and Waterfall. It asks the release API for the {@code bungee} bundle,
 * verifies it like on every platform, and starts it in its own class loader.
 *
 * <p>BungeeCord has no update folder, so a newer loader is staged as a {@code .pending} jar and
 * swapped in at the next start, the same way as on Velocity.
 */
public class BungeeLoaderPlugin extends Plugin implements PlatformHandle {

    /** The value the release API and the bundle use for this platform. */
    public static final String PLATFORM = "bungee";

    private LoaderLogger loaderLogger;
    private volatile LoaderLifecycle lifecycle;
    private volatile CommandDelegate commandDelegate;

    @Override
    public void onLoad() {
        this.loaderLogger = new LoaderLogger() {
            @Override
            public void info(String message) {
                getLogger().info(message);
            }

            @Override
            public void warn(String message) {
                getLogger().warning(message);
            }

            @Override
            public void error(String message) {
                getLogger().severe(message);
            }

            @Override
            public void error(String message, Throwable throwable) {
                getLogger().log(Level.SEVERE, message, throwable);
            }
        };
    }

    @Override
    public void onEnable() {
        LoaderLifecycle created = new LoaderLifecycle(this);
        this.lifecycle = created;
        getProxy().getPluginManager().registerCommand(this, new BungeeLoaderCommand(created));
        created.onEnable();
    }

    @Override
    public void onDisable() {
        LoaderLifecycle current = lifecycle;
        if (current != null) {
            current.onDisable();
        }
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public String loaderVersion() {
        return getDescription().getVersion();
    }

    @Override
    public Path dataDirectory() {
        return getDataFolder().toPath();
    }

    @Override
    public LoaderLogger logger() {
        return loaderLogger;
    }

    @Override
    public String serverBrand() {
        return getProxy().getName();
    }

    @Override
    public String serverVersion() {
        return getProxy().getVersion();
    }

    @Override
    public Executor asyncExecutor() {
        return runnable -> getProxy().getScheduler().runAsync(this, runnable);
    }

    @Override
    public Object rawServer() {
        return getProxy();
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

    /** {@code mca}, for the console and for players with mcanalytics.admin. */
    static final class BungeeLoaderCommand extends Command implements TabExecutor {
        private final LoaderLifecycle lifecycle;

        BungeeLoaderCommand(LoaderLifecycle lifecycle) {
            super("mca", "mcanalytics.admin", "mcanalytics");
            this.lifecycle = lifecycle;
        }

        @Override
        public void execute(net.md_5.bungee.api.CommandSender sender, String[] args) {
            lifecycle.handleCommand(wrap(sender), args);
        }

        @Override
        public Iterable<String> onTabComplete(net.md_5.bungee.api.CommandSender sender, String[] args) {
            return LoaderCommands.suggest(args);
        }

        // fromLegacyText is deprecated on current BungeeCord but exists on every proxy and fork.
        @SuppressWarnings("deprecation")
        static CommandSender wrap(net.md_5.bungee.api.CommandSender sender) {
            return new CommandSender() {
                @Override
                public void sendMessage(String message) {
                    sender.sendMessage(TextComponent.fromLegacyText(message));
                }

                @Override
                public boolean hasPermission(String permission) {
                    return sender.hasPermission(permission);
                }

                @Override
                public boolean isConsole() {
                    return !(sender instanceof ProxiedPlayer);
                }

                @Override
                public Object rawSource() {
                    return sender;
                }
            };
        }
    }
}
