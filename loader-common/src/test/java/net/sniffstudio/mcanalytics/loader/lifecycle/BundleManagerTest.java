package net.sniffstudio.mcanalytics.loader.lifecycle;

import net.sniffstudio.mcanalytics.loader.api.LoaderLogger;
import net.sniffstudio.mcanalytics.loader.net.ReleaseClient;
import net.sniffstudio.mcanalytics.loader.testsupport.SigningFixture;
import net.sniffstudio.mcanalytics.loader.util.ChecksumUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleManagerTest {

    private final SigningFixture signing = new SigningFixture();
    private final List<String> warnings = new ArrayList<>();
    private final LoaderLogger logger = new LoaderLogger() {
        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void error(String message) {}

        @Override
        public void error(String message, Throwable throwable) {}
    };

    private BundleManager manager(Path dir) {
        return new BundleManager(dir, signing.verifier(), logger);
    }

    private ReleaseClient.ReleaseMetadata meta(String version, byte[] bytes, String signature) {
        return new ReleaseClient.ReleaseMetadata("paper", version, ChecksumUtil.sha256(bytes), bytes.length,
                "/dl", "1.0.0", signature);
    }

    private Path download(Path dir, byte[] bytes) throws Exception {
        Files.createDirectories(dir.resolve("cache"));
        Path temp = Files.createTempFile(dir.resolve("cache"), "connector-dl-", ".tmp");
        Files.write(temp, bytes);
        return temp;
    }

    @Test
    @DisplayName("A jar with a valid signature is installed and gets a sidecar")
    void installsASignedJar(@TempDir Path tempDir) throws Exception {
        byte[] jar = "signed-jar".getBytes(StandardCharsets.UTF_8);
        Path installed = manager(tempDir).install(download(tempDir, jar), meta("1.2.3", jar, signing.sign(jar)));

        assertThat(installed.getFileName()).hasToString("connector-paper-1.2.3.jar");
        assertThat(Files.readAllBytes(installed)).isEqualTo(jar);
        assertThat(BundleManager.sidecarPath(installed)).exists();
        assertThat(manager(tempDir).verificationFailure(installed)).isNull();
    }

    @Test
    @DisplayName("A jar with a bad signature is refused and nothing is installed")
    void refusesABadSignature(@TempDir Path tempDir) throws Exception {
        byte[] jar = "signed-jar".getBytes(StandardCharsets.UTF_8);
        String signatureOfOtherBytes = signing.sign("other-bytes".getBytes(StandardCharsets.UTF_8));
        String signatureByAnotherKey = SigningFixture.sign(SigningFixture.newKeyPair(), jar);

        for (String bad : new String[]{signatureOfOtherBytes, signatureByAnotherKey}) {
            Path temp = download(tempDir, jar);
            assertThatThrownBy(() -> manager(tempDir).install(temp, meta("1.2.3", jar, bad)))
                    .isInstanceOf(ReleaseClient.ReleaseRejectedException.class)
                    .hasMessageContaining("Signature verification failed");
            assertThat(tempDir.resolve("cache").resolve("connector-paper-1.2.3.jar")).doesNotExist();
        }
    }

    @Test
    @DisplayName("A matching checksum with the wrong bytes, or the wrong size, is refused before the signature")
    void refusesChecksumAndSizeMismatch(@TempDir Path tempDir) throws Exception {
        byte[] jar = "signed-jar".getBytes(StandardCharsets.UTF_8);
        byte[] other = "signed-jaR".getBytes(StandardCharsets.UTF_8);
        Path temp = download(tempDir, other);

        assertThatThrownBy(() -> manager(tempDir).install(temp, meta("1.2.3", jar, signing.sign(jar))))
                .isInstanceOf(ReleaseClient.ReleaseRejectedException.class)
                .hasMessageContaining("Checksum mismatch");

        ReleaseClient.ReleaseMetadata wrongSize = new ReleaseClient.ReleaseMetadata("paper", "1.2.3",
                ChecksumUtil.sha256(other), other.length + 1, "/dl", "1.0.0", signing.sign(other));
        assertThatThrownBy(() -> manager(tempDir).install(temp, wrongSize))
                .isInstanceOf(ReleaseClient.ReleaseRejectedException.class)
                .hasMessageContaining("size mismatch");
    }

    @Test
    @DisplayName("A cached jar edited after install fails verification")
    void detectsATamperedCachedJar(@TempDir Path tempDir) throws Exception {
        Path jar = signing.seedCache(tempDir, "paper", "1.0.0", "original-jar".getBytes(StandardCharsets.UTF_8));
        assertThat(manager(tempDir).verificationFailure(jar)).isNull();

        Files.writeString(jar, "malicious-jar");

        assertThat(manager(tempDir).verificationFailure(jar)).contains("checksum does not match");
        assertThat(manager(tempDir).findNewestValidCachedBundle("paper")).isEmpty();
        assertThat(warnings).anyMatch(w -> w.contains("Skipping saved connector connector-paper-1.0.0.jar"));
    }

    @Test
    @DisplayName("A tampered jar with its sidecar rewritten to match still fails the signature")
    void detectsAJarWithARewrittenSidecar(@TempDir Path tempDir) throws Exception {
        Path jar = signing.seedCache(tempDir, "paper", "1.0.0", "original-jar".getBytes(StandardCharsets.UTF_8));
        byte[] malicious = "malicious-jar".getBytes(StandardCharsets.UTF_8);
        Files.write(jar, malicious);
        String originalSignature = signing.sign("original-jar".getBytes(StandardCharsets.UTF_8));
        manager(tempDir).writeSidecar(jar, ChecksumUtil.sha256(malicious), originalSignature);

        assertThat(manager(tempDir).verificationFailure(jar)).contains("signature is not valid");
        assertThat(manager(tempDir).findNewestValidCachedBundle("paper")).isEmpty();
    }

    @Test
    @DisplayName("A jar signed by a key the loader does not trust is skipped")
    void skipsAJarSignedByAnotherKey(@TempDir Path tempDir) throws Exception {
        byte[] bytes = "foreign-jar".getBytes(StandardCharsets.UTF_8);
        Path jar = manager(tempDir).getBundlePath("paper", "1.0.0");
        Files.write(jar, bytes);
        manager(tempDir).writeSidecar(jar, ChecksumUtil.sha256(bytes), SigningFixture.sign(SigningFixture.newKeyPair(), bytes));

        assertThat(manager(tempDir).findNewestValidCachedBundle("paper")).isEmpty();
    }

    @Test
    @DisplayName("A jar with no sidecar is never loaded")
    void skipsAJarWithoutASidecar(@TempDir Path tempDir) throws Exception {
        Files.writeString(manager(tempDir).getBundlePath("paper", "1.0.0"), "no-record");

        assertThat(manager(tempDir).findNewestValidCachedBundle("paper")).isEmpty();
        assertThat(warnings).anyMatch(w -> w.contains("no verification record"));
    }

    @Test
    @DisplayName("A damaged or incomplete sidecar makes the jar unusable")
    void skipsAJarWithABrokenSidecar(@TempDir Path tempDir) throws Exception {
        Path jar = signing.seedCache(tempDir, "paper", "1.0.0", "jar".getBytes(StandardCharsets.UTF_8));
        Path sidecar = BundleManager.sidecarPath(jar);

        Files.writeString(sidecar, "not json");
        assertThat(manager(tempDir).verificationFailure(jar)).contains("cannot be read");
        Files.writeString(sidecar, "{}");
        assertThat(manager(tempDir).verificationFailure(jar)).contains("incomplete");
        Files.writeString(sidecar, "{\"sha256\":\"" + "0".repeat(64) + "\",\"signature\":\"AAAA\"}");
        assertThat(manager(tempDir).verificationFailure(jar)).isNotNull();
    }

    @Test
    @DisplayName("A tampered newest jar falls back to the older jar that still verifies")
    void fallsBackPastATamperedJar(@TempDir Path tempDir) throws Exception {
        Path older = signing.seedCache(tempDir, "paper", "1.0.0", "older".getBytes(StandardCharsets.UTF_8));
        Path newer = signing.seedCache(tempDir, "paper", "1.1.0", "newer".getBytes(StandardCharsets.UTF_8));
        assertThat(manager(tempDir).findNewestValidCachedBundle("paper")).contains(newer);

        Files.writeString(newer, "tampered");

        assertThat(manager(tempDir).findNewestValidCachedBundle("paper")).contains(older);
    }

    @Test
    @DisplayName("matchesRelease accepts the exact release and rejects a changed jar")
    void matchesReleaseChecksEverything(@TempDir Path tempDir) throws Exception {
        byte[] jar = "release-jar".getBytes(StandardCharsets.UTF_8);
        Path path = manager(tempDir).getBundlePath("paper", "2.0.0");
        Files.write(path, jar);
        ReleaseClient.ReleaseMetadata meta = meta("2.0.0", jar, signing.sign(jar));

        assertThat(manager(tempDir).matchesRelease(path, meta)).isTrue();
        assertThat(manager(tempDir).verificationFailure(path)).isNull();

        Files.writeString(path, "release-jaR");
        assertThat(manager(tempDir).matchesRelease(path, meta)).isFalse();
    }

    @Test
    @DisplayName("A missing or empty bundle is never valid")
    void rejectsMissingAndEmptyBundles(@TempDir Path tempDir) throws Exception {
        BundleManager manager = manager(tempDir);

        assertThat(manager.verificationFailure(tempDir.resolve("absent.jar"))).isNotNull();

        Path empty = manager.getBundlePath("paper", "0.0.1");
        Files.createFile(empty);
        assertThat(manager.verificationFailure(empty)).isNotNull();
    }

    @Test
    @DisplayName("The newest verified cached bundle for the platform wins")
    void picksNewestCachedBundle(@TempDir Path tempDir) throws Exception {
        signing.seedCache(tempDir, "paper", "1.0.0", "a".getBytes(StandardCharsets.UTF_8));
        signing.seedCache(tempDir, "paper", "1.10.0", "b".getBytes(StandardCharsets.UTF_8));
        signing.seedCache(tempDir, "paper", "1.2.0", "c".getBytes(StandardCharsets.UTF_8));
        signing.seedCache(tempDir, "velocity", "9.9.9", "d".getBytes(StandardCharsets.UTF_8));

        Path newest = manager(tempDir).findNewestValidCachedBundle("paper").orElseThrow();
        assertThat(newest.getFileName()).hasToString("connector-paper-1.10.0.jar");
    }

    @Test
    @DisplayName("A cache file whose name carries a non-strict version is ignored")
    void ignoresOddlyNamedFiles(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("cache"));
        Files.writeString(tempDir.resolve("cache").resolve("connector-paper-latest.jar"), "x");
        Files.writeString(tempDir.resolve("cache").resolve("connector-paper-1.2.3-evil.jar"), "x");

        assertThat(manager(tempDir).findNewestValidCachedBundle("paper")).isEmpty();
    }

    @Test
    @DisplayName("An empty cache reports no bundle")
    void emptyCacheHasNoBundle(@TempDir Path tempDir) {
        assertThat(manager(tempDir).findNewestValidCachedBundle("paper")).isEmpty();
    }

    @Test
    @DisplayName("A version that is not three plain numbers never becomes a file name")
    void refusesBadVersionStrings(@TempDir Path tempDir) {
        BundleManager manager = manager(tempDir);

        for (String bad : new String[]{"../../evil", "1.2.3/../../evil", "1.2.3\n", "1.2", "1.2.3-SNAPSHOT",
                "1.2.3.4", "", "%2e%2e", "..", "12345.0.0", "1.2.3\\..\\x"}) {
            assertThatThrownBy(() -> manager.getBundlePath("paper", bad))
                    .as("version %s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid connector version");
        }
        assertThatThrownBy(() -> manager.getBundlePath("../paper", "1.2.3"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.getBundlePath(null, "1.2.3"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A good version resolves to a file directly inside the cache folder")
    void goodVersionStaysInsideTheCache(@TempDir Path tempDir) throws Exception {
        BundleManager manager = manager(tempDir);
        Path path = manager.getBundlePath("velocity", "12.34.56");

        assertThat(path.getParent()).isEqualTo(manager.getCacheDir().toAbsolutePath().normalize());
        assertThat(path.getFileName()).hasToString("connector-velocity-12.34.56.jar");
    }
}
