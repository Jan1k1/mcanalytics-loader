package net.sniffstudio.mcanalytics.loader.lifecycle;

import net.sniffstudio.mcanalytics.loader.api.LoaderLogger;
import net.sniffstudio.mcanalytics.loader.net.ReleaseClient;
import net.sniffstudio.mcanalytics.loader.util.BundleVerifier;
import net.sniffstudio.mcanalytics.loader.util.ChecksumUtil;
import net.sniffstudio.mcanalytics.loader.util.FilePermissions;
import net.sniffstudio.mcanalytics.loader.util.TinyJson;
import net.sniffstudio.mcanalytics.loader.util.VersionUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The folder of connector jars the loader has downloaded.
 *
 * <p>A jar in the cache is trusted only through its sidecar file, {@code <jar>.verify.json}, which
 * holds the sha256 and the Ed25519 signature the jar was installed with. Every load, including a
 * fallback to an older jar, checks the jar again against both, so a jar that was swapped or edited
 * on disk after install is skipped.
 */
public final class BundleManager {

    static final String SIDECAR_SUFFIX = ".verify.json";
    private static final Pattern PLATFORM_NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

    private final Path cacheDir;
    private final BundleVerifier verifier;
    private final LoaderLogger logger;

    public BundleManager(Path dataDirectory, BundleVerifier verifier, LoaderLogger logger) {
        this.cacheDir = dataDirectory.resolve("cache");
        this.verifier = verifier;
        this.logger = logger;
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

    static Path sidecarPath(Path bundle) {
        return bundle.resolveSibling(bundle.getFileName() + SIDECAR_SUFFIX);
    }

    /**
     * True when {@code bundle} is exactly the release the server just described: size, sha256 and
     * signature all match. The sidecar is rewritten so later loads can re-verify it.
     */
    public boolean matchesRelease(Path bundle, ReleaseClient.ReleaseMetadata meta) {
        try {
            byte[] bytes = readBundle(bundle);
            if (bytes == null || bytes.length != meta.sizeBytes()) {
                return false;
            }
            if (!ChecksumUtil.matches(ChecksumUtil.sha256(bytes), meta.sha256())
                    || !verifier.verify(bytes, meta.signature())) {
                return false;
            }
            writeSidecar(bundle, meta.sha256(), meta.signature());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Moves a downloaded file into the cache after checking its sha256 and signature, and records
     * both in the sidecar.
     *
     * @return the cache path of the installed jar
     * @throws ReleaseClient.ReleaseRejectedException when the checksum or signature does not hold;
     *         nothing is installed then
     */
    public Path install(Path downloaded, ReleaseClient.ReleaseMetadata meta) throws IOException {
        byte[] bytes = readBundle(downloaded);
        if (bytes == null || bytes.length != meta.sizeBytes()) {
            throw new ReleaseClient.ReleaseRejectedException("Downloaded file size mismatch: expected " + meta.sizeBytes() + " bytes");
        }
        String actual = ChecksumUtil.sha256(bytes);
        if (!ChecksumUtil.matches(actual, meta.sha256())) {
            throw new ReleaseClient.ReleaseRejectedException("Checksum mismatch: expected " + meta.sha256() + ", got " + actual);
        }
        if (!verifier.verify(bytes, meta.signature())) {
            throw new ReleaseClient.ReleaseRejectedException("Signature verification failed: the jar is not signed by a trusted key");
        }

        Path target = getBundlePath(meta.platform(), meta.version());
        // An old record for the same file name must not vouch for the new bytes.
        Files.deleteIfExists(sidecarPath(target));
        Files.move(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
        writeSidecar(target, meta.sha256(), meta.signature());
        return target;
    }

    /** Saves the sha256 and signature a jar was verified with, next to the jar. */
    public void writeSidecar(Path bundle, String sha256, String signature) throws IOException {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("sha256", sha256.toLowerCase(java.util.Locale.ROOT));
        record.put("signature", signature);
        Path sidecar = sidecarPath(bundle);
        Path temp = FilePermissions.createPrivateTempFile(cacheDir, "verify-", ".tmp");
        try {
            Files.writeString(temp, TinyJson.toJson(record), StandardCharsets.UTF_8);
            try {
                Files.move(temp, sidecar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, sidecar, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Checks a cached jar against its sidecar: the jar's sha256 must equal the recorded one and
     * the recorded signature must verify the jar's bytes under a trusted key.
     *
     * @return null when the jar is good, otherwise a short plain reason it is not
     */
    public String verificationFailure(Path bundle) {
        try {
            byte[] bytes = readBundle(bundle);
            if (bytes == null) {
                return "the file is missing, empty or too large";
            }
            Path sidecar = sidecarPath(bundle);
            if (!Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                return "it has no verification record";
            }
            Map<String, Object> record;
            try {
                record = TinyJson.parseObject(Files.readString(sidecar, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException | IOException e) {
                return "its verification record cannot be read";
            }
            String recordedSha = TinyJson.getString(record, "sha256");
            String signature = TinyJson.getString(record, "signature");
            if (recordedSha == null || !SHA256_HEX.matcher(recordedSha).matches() || signature == null) {
                return "its verification record is incomplete";
            }
            if (!ChecksumUtil.matches(ChecksumUtil.sha256(bytes), recordedSha)) {
                return "its checksum does not match the saved record";
            }
            if (!verifier.verify(bytes, signature)) {
                return "its signature is not valid for a trusted key";
            }
            return null;
        } catch (IOException e) {
            return "it cannot be read";
        }
    }

    /**
     * The newest cached jar that passes {@link #verificationFailure}. A jar that fails is logged
     * and skipped, never loaded.
     */
    public Optional<Path> findNewestValidCachedBundle(String platform) {
        if (!Files.isDirectory(cacheDir) || platform == null || !PLATFORM_NAME.matcher(platform).matches()) {
            return Optional.empty();
        }

        String prefix = "connector-" + platform + "-";
        String suffix = ".jar";
        List<Path> candidates = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, prefix + "*" + suffix)) {
            for (Path p : stream) {
                String version = extractVersion(p.getFileName().toString(), prefix, suffix);
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && VersionUtil.isStrictVersion(version)) {
                    candidates.add(p);
                }
            }
        } catch (IOException e) {
            return Optional.empty();
        }

        candidates.sort((p1, p2) -> VersionUtil.compare(
                extractVersion(p2.getFileName().toString(), prefix, suffix),
                extractVersion(p1.getFileName().toString(), prefix, suffix)));

        for (Path candidate : candidates) {
            String failure = verificationFailure(candidate);
            if (failure == null) {
                return Optional.of(candidate);
            }
            logger.warn("[MCAnalytics] Skipping saved connector " + candidate.getFileName() + ": " + failure + ".");
        }
        return Optional.empty();
    }

    /** Reads a jar for verification; null when it is not a regular file, is empty or is over the size limit. */
    private static byte[] readBundle(Path bundle) throws IOException {
        if (!Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        long size = Files.size(bundle);
        if (size <= 0 || size > ReleaseClient.MAX_BUNDLE_BYTES) {
            return null;
        }
        return Files.readAllBytes(bundle);
    }

    private static String extractVersion(String fileName, String prefix, String suffix) {
        if (fileName.startsWith(prefix) && fileName.endsWith(suffix)) {
            return fileName.substring(prefix.length(), fileName.length() - suffix.length());
        }
        return "";
    }
}
