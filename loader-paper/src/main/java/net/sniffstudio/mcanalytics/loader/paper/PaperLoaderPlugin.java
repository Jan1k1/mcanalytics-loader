package net.sniffstudio.mcanalytics.loader.paper;

import net.sniffstudio.mcanalytics.loader.api.CommandDelegate;
import net.sniffstudio.mcanalytics.loader.api.CommandSender;
import net.sniffstudio.mcanalytics.loader.api.LoaderLogger;
import net.sniffstudio.mcanalytics.loader.api.PlatformHandle;
import net.sniffstudio.mcanalytics.loader.lifecycle.LoaderCommands;
import net.sniffstudio.mcanalytics.loader.lifecycle.LoaderLifecycle;
import org.bukkit.command.Command;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Level;

public class PaperLoaderPlugin extends JavaPlugin implements PlatformHandle {

    private static final boolean IS_FOLIA;

    static {
        boolean folia = false;
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
        } catch (Throwable ignored) {
        }
        IS_FOLIA = folia;
    }

    private LoaderLogger loaderLogger;
    private LoaderLifecycle lifecycle;
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

        this.lifecycle = new LoaderLifecycle(this);
    }

    @Override
    public void onEnable() {
        registerMcaCommand();
        lifecycle.onEnable();
    }

    @Override
    public void onDisable() {
        if (lifecycle != null) {
            lifecycle.onDisable();
        }
    }

    private void registerMcaCommand() {
        PaperLoaderCommand loaderCmd = new PaperLoaderCommand(lifecycle);
        boolean registered = false;
        try {
            registered = getServer().getCommandMap().register("mcanalytics", loaderCmd);
        } catch (Throwable ignored) {}

        if (!registered) {
            PluginCommand cmd = getCommand("mca");
            if (cmd != null) {
                cmd.setExecutor((sender, command, label, args) -> lifecycle.handleCommand(wrap(sender), args));
                cmd.setTabCompleter((sender, command, label, args) -> LoaderCommands.suggest(args));
            }
        }
    }

    private static CommandSender wrap(org.bukkit.command.CommandSender sender) {
        return new CommandSender() {
            @Override
            public void sendMessage(String message) {
                sender.sendMessage(message);
            }

            @Override
            public boolean hasPermission(String permission) {
                return sender.hasPermission(permission);
            }

            @Override
            public boolean isConsole() {
                return sender instanceof ConsoleCommandSender;
            }

            @Override
            public Object rawSource() {
                return sender;
            }
        };
    }

    @Override
    public String platform() {
        return "paper";
    }

    @Override
    public String loaderVersion() {
        return getPluginMeta().getVersion();
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
        return getServer().getName();
    }

    @Override
    public String serverVersion() {
        return getServer().getVersion();
    }

    @Override
    public Executor asyncExecutor() {
        if (IS_FOLIA) {
            return runnable -> org.bukkit.Bukkit.getAsyncScheduler().runNow(this, task -> runnable.run());
        }
        return runnable -> getServer().getScheduler().runTaskAsynchronously(this, runnable);
    }

    @Override
    public Object rawServer() {
        return getServer();
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

    private static final class PaperLoaderCommand extends Command {
        private final LoaderLifecycle lifecycle;

        PaperLoaderCommand(LoaderLifecycle lifecycle) {
            super("mca", "MCAnalytics loader command", "/mca [pair|status|update]", List.of("mcanalytics"));
            this.lifecycle = lifecycle;
            setPermission("mcanalytics.admin");
        }

        @Override
        public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
            return lifecycle.handleCommand(wrap(sender), args);
        }

        @Override
        public List<String> tabComplete(org.bukkit.command.CommandSender sender, String alias, String[] args) {
            return LoaderCommands.suggest(args);
        }
    }
}
