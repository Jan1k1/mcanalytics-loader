package net.sniffstudio.mcanalytics.loader.api;

public interface CommandDelegate {
    boolean onCommand(CommandSender sender, String[] args);
}
