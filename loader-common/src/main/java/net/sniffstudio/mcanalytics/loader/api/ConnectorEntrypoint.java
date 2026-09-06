package net.sniffstudio.mcanalytics.loader.api;

public interface ConnectorEntrypoint {
    void start(PlatformHandle handle);
    void stop();
}
