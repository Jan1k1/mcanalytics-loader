package net.sniffstudio.mcanalytics.loader.update;

import net.sniffstudio.mcanalytics.loader.net.ReleaseClient;
import net.sniffstudio.mcanalytics.loader.util.BundleVerifier;
import net.sniffstudio.mcanalytics.loader.util.ChecksumUtil;
import net.sniffstudio.mcanalytics.loader.util.TinyJson;
import net.sniffstudio.mcanalytics.loader.util.VersionUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Puts a verified newer loader jar where the next restart picks it up, and never runs it now.
 *
 * <p><b>Paper.</b> The jar goes into the update folder ({@code plugins/update} unless the server
 * moved it) under exactly the file name of the running jar. Paper copies a file of the same name
 * over the plugin before it loads plugins, so the swap needs a restart and nothing else.
 *
 * <p><b>Velocity</b> has no update folder. The jar is written next to the running jar as
 * {@code <jar name>.pending}, which Velocity ignores because it only loads {@code *.jar}, plus a
 * record {@code <jar name>.pending.verify.json} with its sha256, signature and version. On
 * shutdown, and again at the next start if the shutdown never happened cleanly, the pending file
 * is verified once more and then moved over the running jar's file name in one atomic rename.
 * The swap replaces the file instead of adding one, so two loaders can never be loaded, and a
 * pending file that fails any check is deleted instead of installed.
 *
 * <p>Every path written is inside the plugins folder (the folder of the running jar), a jar is
 * only staged when its version is higher than the running one, and a jar must carry the loader's
 * own plugin descriptor with the version the release manifest named.
 */
public final class LoaderUpdater {

    public enum Outcome { STAGED, ALREADY_STAGED, NOT_NEWER, REFUSED, FAILED }

    public record StageResult(Outcome outcome, String detail) {}

    public enum ApplyOutcome { NONE, APPLIED, DISCARDED, FAILED }

    public record ApplyResult(ApplyOutcome outcome, String detail) {}

    static final String PENDING_SUFFIX = ".pending";
    static final String PENDING_RECORD_SUFFIX = ".pending.verify.json";
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

    private final String platform;
    private final String currentVersion;
    private final Path runningJar;
    private final Path pluginsFolder;
    private final Path configuredUpdateFolder;
    private final BundleVerifier verifier;

    /**
     * @param runningJar the jar this loader runs from, already validated by {@link LoaderJarLocator}
     * @param updateFolder Paper's update folder, or null for {@code <plugins>/update}; ignored on Velocity
     */
    public LoaderUpdater(String platform, String currentVersion, Path runningJar, Path updateFolder, BundleVerifier verifier) {
        this.platform = platform;
        this.currentVersion = currentVersion;
        this.runningJar = runningJar.toAbsolutePath().normalize();
        this.pluginsFolder = this.runningJar.getParent();
        this.configuredUpdateFolder = updateFolder;
        this.verifier = verifier;
    }

    public Path runningJar() {
        return runningJar;
    }

    /** Where a staged jar is written: the update folder file on Paper, the {@code .pending} file on Velocity. */
    public Path stagedPath() {
        return usesUpdateFolder() ? updateFolder().resolve(jarName()) : pendingPath();
    }

    Path pendingPath() {
        return pluginsFolder.resolve(jarName() + PENDING_SUFFIX);
    }

    Path pendingRecordPath() {
        return pluginsFolder.resolve(jarName() + PENDING_RECORD_SUFFIX);
    }

    private String jarName() {
        return runningJar.getFileName().toString();
    }

    private boolean usesUpdateFolder() {
        return "paper".equals(platform);
    }

    private Path updateFolder() {
        Path folder = configuredUpdateFolder != null ? configuredUpdateFolder : pluginsFolder.resolve("update");
        return folder.toAbsolutePath().normalize();
    }

    /**
     * The newest version already waiting for a restart: a valid staged jar, or the jar on disk
     * under the running jar's name when it is newer than the running loader (a swap that already
     * happened but has not been restarted into yet).
     */
    public Optional<String> stagedVersion() {
        Optional<String> best = Optional.empty();
        try {
            byte[] onDisk = readJar(runningJar);
            if (onDisk != null) {
                best = LoaderJarDescriptor.loaderVersion(onDisk, platform);
            }
        } catch (IOException | RuntimeException ignored) {
            // Not readable: it counts for nothing.
        }
        Optional<String> staged = Optional.empty();
        try {
            if (usesUpdateFolder()) {
                byte[] existing = readJar(updateFolder().resolve(jarName()));
                staged = existing == null ? Optional.empty() : LoaderJarDescriptor.loaderVersion(existing, platform);
            } else if (applyCheck(pendingPath(), pendingRecordPath()).isEmpty()) {
                staged = readPendingVersion();
            }
        } catch (IOException | RuntimeException ignored) {
            // Not readable: it counts for nothing.
        }
        if (staged.isPresent() && (best.isEmpty() || VersionUtil.isNewer(best.get(), staged.get()))) {
            best = staged;
        }
        return best;
    }

    /**
     * Verifies {@code downloaded} and stages it. The file is read, never moved, so the caller
     * deletes it afterwards.
     */
    public StageResult stage(Path downloaded, ReleaseClient.ReleaseMetadata meta) {
        if (!VersionUtil.isNewer(currentVersion, meta.version())) {
            return new StageResult(Outcome.NOT_NEWER, "loader " + meta.version() + " is not newer than " + currentVersion);
        }
        byte[] bytes;
        try {
            bytes = readJar(downloaded);
        } catch (IOException e) {
            return new StageResult(Outcome.FAILED, "the downloaded file cannot be read");
        }
        String refusal = verify(bytes, meta.sha256(), meta.signature(), meta.sizeBytes(), meta.version());
        if (refusal != null) {
            return new StageResult(Outcome.REFUSED, refusal);
        }
        try {
            return usesUpdateFolder() ? stageInUpdateFolder(bytes, meta) : stagePending(bytes, meta);
        } catch (IOException | RuntimeException e) {
            return new StageResult(Outcome.FAILED, "could not write the update: " + e.getClass().getSimpleName());
        }
    }

    /** Every check a jar must pass before it is staged or swapped in; null when it passes. */
    private String verify(byte[] bytes, String sha256, String signature, long expectedSize, String expectedVersion) {
        if (bytes == null) {
            return "the file is missing, empty or too large";
        }
        if (expectedSize >= 0 && bytes.length != expectedSize) {
            return "the size does not match the release information";
        }
        if (!ChecksumUtil.matches(ChecksumUtil.sha256(bytes), sha256)) {
            return "the checksum does not match the release information";
        }
        if (!verifier.verify(bytes, signature)) {
            return "the signature is not valid for a trusted key";
        }
        Optional<String> declared = LoaderJarDescriptor.loaderVersion(bytes, platform);
        if (declared.isEmpty()) {
            return "the jar is not an MCAnalytics loader for " + platform;
        }
        if (!declared.get().equals(expectedVersion)) {
            return "the jar says it is version " + declared.get() + ", not " + expectedVersion;
        }
        return null;
    }

    private StageResult stageInUpdateFolder(byte[] bytes, ReleaseClient.ReleaseMetadata meta) throws IOException {
        Path folder = updateFolder();
        if (folder.equals(pluginsFolder) || !folder.startsWith(pluginsFolder)) {
            return new StageResult(Outcome.REFUSED, "the update folder is not inside the plugins folder");
        }
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return new StageResult(Outcome.REFUSED, "the update folder is a link or not a folder");
        }
        Files.createDirectories(folder);
        if (!folder.toRealPath().startsWith(pluginsFolder.toRealPath()) || folder.toRealPath().equals(pluginsFolder.toRealPath())) {
            return new StageResult(Outcome.REFUSED, "the update folder resolves outside the plugins folder");
        }
        Path target = folder.resolve(jarName());
        if (!target.getFileName().toString().equals(jarName())) {
            return new StageResult(Outcome.REFUSED, "the staged file name does not match the running jar");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                return new StageResult(Outcome.REFUSED, "the staged path is not a regular file");
            }
            byte[] existing = readJar(target);
            if (existing != null) {
                if (ChecksumUtil.matches(ChecksumUtil.sha256(existing), meta.sha256())) {
                    return new StageResult(Outcome.ALREADY_STAGED, "loader " + meta.version() + " is already staged");
                }
                Optional<String> staged = LoaderJarDescriptor.loaderVersion(existing, platform);
                if (staged.isPresent() && !VersionUtil.isNewer(staged.get(), meta.version())) {
                    return new StageResult(Outcome.ALREADY_STAGED, "loader " + staged.get() + " is already staged");
                }
            }
        }
        writeAtomically(folder, target, bytes);
        return new StageResult(Outcome.STAGED, "staged " + target);
    }

    private StageResult stagePending(byte[] bytes, ReleaseClient.ReleaseMetadata meta) throws IOException {
        Path pending = pendingPath();
        Path record = pendingRecordPath();
        if (!pending.getParent().equals(pluginsFolder) || !record.getParent().equals(pluginsFolder)) {
            return new StageResult(Outcome.REFUSED, "the pending file would be outside the plugins folder");
        }
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(pending, LinkOption.NOFOLLOW_LINKS)) {
            return new StageResult(Outcome.REFUSED, "the pending path is not a regular file");
        }
        Optional<String> pendingVersion = readPendingVersion();
        if (pendingVersion.isPresent() && !VersionUtil.isNewer(pendingVersion.get(), meta.version())
                && applyCheck(pending, record).isEmpty()) {
            return new StageResult(Outcome.ALREADY_STAGED, "loader " + pendingVersion.get() + " is already pending");
        }
        writeAtomically(pluginsFolder, pending, bytes);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", meta.version());
        data.put("sha256", meta.sha256().toLowerCase(Locale.ROOT));
        data.put("signature", meta.signature());
        writeAtomically(pluginsFolder, record, TinyJson.toJson(data).getBytes(StandardCharsets.UTF_8));
        return new StageResult(Outcome.STAGED, "staged " + pending);
    }

    /**
     * Velocity only. Swaps a pending jar in, or deletes it when it fails a check, is not newer
     * than the running loader, or is a leftover without its record.
     */
    public ApplyResult applyPending() {
        if (usesUpdateFolder()) {
            return new ApplyResult(ApplyOutcome.NONE, "");
        }
        Path pending = pendingPath();
        Path record = pendingRecordPath();
        if (!Files.exists(pending, LinkOption.NOFOLLOW_LINKS) && !Files.exists(record, LinkOption.NOFOLLOW_LINKS)) {
            return new ApplyResult(ApplyOutcome.NONE, "");
        }
        Optional<String> problem = applyCheck(pending, record);
        if (problem.isPresent()) {
            deletePending();
            return new ApplyResult(ApplyOutcome.DISCARDED, problem.get());
        }
        String version = readPendingVersion().orElse("");
        if (!Files.isRegularFile(runningJar, LinkOption.NOFOLLOW_LINKS)) {
            // The operator moved or renamed the running jar. Writing under its old name would
            // add a second loader, so nothing is swapped.
            return new ApplyResult(ApplyOutcome.FAILED, "the running loader jar is no longer at " + runningJar.getFileName());
        }
        try {
            try {
                Files.move(pending, runningJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(pending, runningJar, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            return new ApplyResult(ApplyOutcome.FAILED, "could not replace " + runningJar.getFileName()
                    + " (" + e.getClass().getSimpleName() + "); the pending file stays in place and nothing else changes");
        }
        try {
            Files.deleteIfExists(record);
        } catch (IOException ignored) {
        }
        return new ApplyResult(ApplyOutcome.APPLIED, version);
    }

    /** Removes a pending jar and its record, for example when the operator turned self-update off. */
    public boolean discardPending() {
        boolean existed = Files.exists(pendingPath(), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(pendingRecordPath(), LinkOption.NOFOLLOW_LINKS);
        deletePending();
        return existed;
    }

    private void deletePending() {
        for (Path p : new Path[]{pendingPath(), pendingRecordPath()}) {
            try {
                if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(p);
                }
            } catch (IOException ignored) {
            }
        }
    }

    /** Empty when the pending jar may be swapped in, otherwise the plain reason it may not. */
    private Optional<String> applyCheck(Path pending, Path record) {
        try {
            if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of("the pending update has no verification record");
            }
            Map<String, Object> data;
            try {
                data = TinyJson.parseObject(Files.readString(record, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                return Optional.of("the pending update's verification record cannot be read");
            }
            String version = TinyJson.getString(data, "version");
            String sha = TinyJson.getString(data, "sha256");
            String signature = TinyJson.getString(data, "signature");
            if (!VersionUtil.isStrictVersion(version) || sha == null || !SHA256_HEX.matcher(sha).matches() || signature == null) {
                return Optional.of("the pending update's verification record is incomplete");
            }
            if (!VersionUtil.isNewer(currentVersion, version)) {
                return Optional.of("the pending loader " + version + " is not newer than the running " + currentVersion);
            }
            byte[] bytes = readJar(pending);
            String refusal = verify(bytes, sha, signature, -1, version);
            return Optional.ofNullable(refusal == null ? null : "the pending update was not swapped in: " + refusal);
        } catch (IOException e) {
            return Optional.of("the pending update cannot be read");
        }
    }

    private Optional<String> readPendingVersion() {
        try {
            Path record = pendingRecordPath();
            if (!Files.isRegularFile(record, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            String version = TinyJson.getString(TinyJson.parseObject(Files.readString(record, StandardCharsets.UTF_8)), "version");
            return VersionUtil.isStrictVersion(version) ? Optional.of(version) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Writes to a temporary file in the target's own folder, then renames it into place. */
    private static void writeAtomically(Path folder, Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(folder, ".mca-loader-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Reads a regular file of a sane size; null when it is not one, is empty or is over the cap. */
    private static byte[] readJar(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        long size = Files.size(file);
        if (size <= 0 || size > ReleaseClient.MAX_BUNDLE_BYTES) {
            return null;
        }
        return Files.readAllBytes(file);
    }
}
