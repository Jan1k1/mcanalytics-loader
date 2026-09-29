package net.sniffstudio.mcanalytics.loader.util;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class FilePermissions {

    private static final Set<PosixFilePermission> OWNER_READ_WRITE =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private FilePermissions() {}

    /**
     * Creates an empty file that only its owner can read or write, and never exists with wider
     * permissions.
     *
     * <p>On POSIX the file is created with mode {@code rw-------} in the same call, so there is no
     * moment when another user could read what is written into it. A credential file has no use
     * for an execute bit, so none is set. On Windows the file is created empty and its ACL is cut
     * down to the owner before the caller writes anything, which gives the same guarantee for the
     * content. Where neither mechanism exists the file is created as usual.
     */
    public static Path createPrivateTempFile(Path directory, String prefix, String suffix) throws IOException {
        if (supportsPosix()) {
            return Files.createTempFile(directory, prefix, suffix,
                    PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE));
        }
        Path file = Files.createTempFile(directory, prefix, suffix);
        restrictToOwner(file);
        return file;
    }

    /**
     * Cuts a file down to its owner: {@code rw-------} on POSIX, an owner-only ACL where the file
     * system has ACLs (Windows).
     *
     * @return true when the restriction was applied
     */
    public static boolean restrictToOwner(Path path) {
        if (path == null) {
            return false;
        }
        try {
            if (supportsPosix()) {
                Files.setPosixFilePermissions(path, OWNER_READ_WRITE);
                return true;
            }
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (acl == null) {
                return false;
            }
            UserPrincipal owner = Files.getOwner(path);
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(ownerOnly));
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    public static boolean supportsPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }
}
