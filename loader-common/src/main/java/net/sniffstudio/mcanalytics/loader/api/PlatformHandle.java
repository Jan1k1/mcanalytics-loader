package net.sniffstudio.mcanalytics.loader.api;

import java.nio.file.Path;
import java.util.concurrent.Executor;

public interface PlatformHandle {
    String platform();
    String loaderVersion();
    Path dataDirectory();
    LoaderLogger logger();
    String serverBrand();
    String serverVersion();
    Executor asyncExecutor();
    Object rawServer();
    Object rawPlugin();
    void setCommandDelegate(CommandDelegate delegate);
    CommandDelegate getCommandDelegate();

    /**
     * The folder the platform swaps updated plugin jars in from at the next start (Paper's update
     * folder), or null when the platform has none or the loader should use {@code plugins/update}.
     */
    default Path updateFolder() {
        return null;
    }
}
