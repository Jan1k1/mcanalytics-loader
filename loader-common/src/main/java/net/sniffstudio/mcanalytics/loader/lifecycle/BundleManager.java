package net.sniffstudio.mcanalytics.loader.lifecycle;

import net.sniffstudio.mcanalytics.loader.util.ChecksumUtil;
import net.sniffstudio.mcanalytics.loader.util.VersionUtil;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class BundleManager {

    private static final Pattern PLATFORM_NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    private final Path cacheDir;

    public BundleManager(Path dataDirectory) {
        this.cacheDir = dataDirectory.resolve("cache");
    }

    public Path getCacheDir() {
        return cacheDir;
    }

    /**
     * The cache path for a connector version. The version comes from the server, so it must be
     * three plain numbers and the resolved path must stay inside the cache folder.
     *
     * @throws IllegalArgumentException when the platform or version is not acceptable
     */
    public Path getBundlePath(String platform, String version) throws IOException {
        if (platform == null || !PLATFORM_NAME.matcher(platform).matches()) {
            throw new IllegalArgumentException("Invalid platform name");
        }
        if (!VersionUtil.isStrictVersion(version)) {
            throw new IllegalArgumentException("Invalid connector version");
        }
        Files.createDirectories(cacheDir);
        Path root = cacheDir.toAbsolutePath().normalize();
        Path resolved = root.resolve("connector-" + platform + "-" + version + ".jar").normalize();
        if (!resolved.startsWith(root) || !root.equals(resolved.getParent())) {
            throw new IllegalArgumentException("Connector path escapes the cache folder");
        }
        return resolved;
    }

    public boolean isBundleValid(Path bundlePath, String expectedSha256) {
        if (!Files.isRegularFile(bundlePath)) {
            return false;
        }
        try {
            long size = Files.size(bundlePath);
            if (size <= 0) {
                return false;
            }
            if (expectedSha256 != null && !expectedSha256.isBlank()) {
                String actualSha = ChecksumUtil.sha256(bundlePath);
                return ChecksumUtil.matches(actualSha, expectedSha256);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public Optional<Path> findNewestValidCachedBundle(String platform) {
        if (!Files.isDirectory(cacheDir)) {
            return Optional.empty();
        }

        String prefix = "connector-" + platform + "-";
        String suffix = ".jar";
        List<Path> candidates = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "connector-" + platform + "-*.jar")) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && Files.size(p) > 0) {
                    candidates.add(p);
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        candidates.sort((p1, p2) -> {
            String name1 = p1.getFileName().toString();
            String name2 = p2.getFileName().toString();
            String ver1 = extractVersion(name1, prefix, suffix);
            String ver2 = extractVersion(name2, prefix, suffix);
            return VersionUtil.compare(ver2, ver1);
        });

        return Optional.of(candidates.get(0));
    }

    private static String extractVersion(String fileName, String prefix, String suffix) {
        if (fileName.startsWith(prefix) && fileName.endsWith(suffix)) {
            return fileName.substring(prefix.length(), fileName.length() - suffix.length());
        }
        return "0.0.0";
    }
}
