package net.sniffstudio.mcanalytics.loader.lifecycle;

import net.sniffstudio.mcanalytics.loader.util.ChecksumUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BundleManagerTest {

    @Test
    @DisplayName("A cached bundle is accepted only when its checksum matches")
    void acceptsOnlyMatchingChecksum(@TempDir Path tempDir) throws Exception {
        BundleManager manager = new BundleManager(tempDir);
        Path bundle = manager.getBundlePath("paper", "1.2.3");
        byte[] payload = "bundle-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(bundle, payload);

        assertThat(manager.isBundleValid(bundle, ChecksumUtil.sha256(payload))).isTrue();
        assertThat(manager.isBundleValid(bundle, "not-the-right-hash")).isFalse();
    }

    @Test
    @DisplayName("A missing or empty bundle is never valid")
    void rejectsMissingAndEmptyBundles(@TempDir Path tempDir) throws Exception {
        BundleManager manager = new BundleManager(tempDir);

        assertThat(manager.isBundleValid(tempDir.resolve("absent.jar"), null)).isFalse();

        Path empty = manager.getBundlePath("paper", "0.0.1");
        Files.createFile(empty);
        assertThat(manager.isBundleValid(empty, null)).isFalse();
    }

    @Test
    @DisplayName("The newest cached bundle for the platform wins")
    void picksNewestCachedBundle(@TempDir Path tempDir) throws Exception {
        BundleManager manager = new BundleManager(tempDir);
        Files.writeString(manager.getBundlePath("paper", "1.0.0"), "a");
        Files.writeString(manager.getBundlePath("paper", "1.10.0"), "b");
        Files.writeString(manager.getBundlePath("paper", "1.2.0"), "c");
        Files.writeString(manager.getBundlePath("velocity", "9.9.9"), "d");

        Path newest = manager.findNewestValidCachedBundle("paper").orElseThrow();
        assertThat(newest.getFileName()).hasToString("connector-paper-1.10.0.jar");
    }

    @Test
    @DisplayName("An empty cache reports no bundle")
    void emptyCacheHasNoBundle(@TempDir Path tempDir) {
        assertThat(new BundleManager(tempDir).findNewestValidCachedBundle("paper")).isEmpty();
    }
}
