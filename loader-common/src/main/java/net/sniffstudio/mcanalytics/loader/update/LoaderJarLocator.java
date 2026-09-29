package net.sniffstudio.mcanalytics.loader.update;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Optional;
import java.util.regex.Pattern;

/** Finds the jar file the running loader was loaded from. */
public final class LoaderJarLocator {

    /** A plain jar file name. Anything odd means the loader does not touch its own jar. */
    private static final Pattern SAFE_JAR_NAME = Pattern.compile("[A-Za-z0-9._+\\-]{1,120}\\.jar");

    private LoaderJarLocator() {}

    /**
     * The jar {@code type} was loaded from, through its protection domain's code source.
     *
     * @return empty when the class was not loaded from a regular {@code .jar} file with a plain
     *         name (a development run from a folder, a symbolic link, an unusual name)
     */
    public static Optional<Path> locate(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return Optional.empty();
            }
            return validate(Paths.get(source.getLocation().toURI()));
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static Optional<Path> validate(Path candidate) {
        Path jar = candidate.toAbsolutePath().normalize();
        Path name = jar.getFileName();
        if (name == null || jar.getParent() == null || !SAFE_JAR_NAME.matcher(name.toString()).matches()) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(jar);
    }
}
