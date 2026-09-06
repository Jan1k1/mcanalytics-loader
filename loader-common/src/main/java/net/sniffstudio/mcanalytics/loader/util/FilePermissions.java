package net.sniffstudio.mcanalytics.loader.util;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

public final class FilePermissions {

    private static final Set<PosixFilePermission> OWNER_READ_WRITE =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private FilePermissions() {}

    public static boolean restrictToOwner(Path path) {
        if (path == null || !supportsPosix()) {
            return false;
        }
        try {
            Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    public static boolean supportsPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}
