package net.sniffstudio.mcanalytics.loader.api;

public interface CommandSender {
    void sendMessage(String message);
    boolean hasPermission(String permission);
    boolean isConsole();
    default Object rawSource() {
        return null;
    }
}
