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
}
