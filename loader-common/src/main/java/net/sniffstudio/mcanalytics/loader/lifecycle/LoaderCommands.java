package net.sniffstudio.mcanalytics.loader.lifecycle;

import java.util.List;
import java.util.Locale;

public final class LoaderCommands {

    public static final List<String> SUBCOMMANDS = List.of("pair", "status", "update");

    private LoaderCommands() {}

    public static List<String> suggest(String[] args) {
        if (args == null || args.length == 0) {
            return SUBCOMMANDS;
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
