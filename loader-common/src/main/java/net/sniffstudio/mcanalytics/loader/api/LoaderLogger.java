package net.sniffstudio.mcanalytics.loader.api;

public interface LoaderLogger {
    void info(String message);
    void warn(String message);
    void error(String message);
    void error(String message, Throwable throwable);
}
