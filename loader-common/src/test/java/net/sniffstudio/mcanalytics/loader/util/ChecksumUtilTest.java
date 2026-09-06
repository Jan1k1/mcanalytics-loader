package net.sniffstudio.mcanalytics.loader.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChecksumUtilTest {

    @Test
    void computesSha256MatchesReference() {
        byte[] data = "test-content-for-checksum".getBytes(StandardCharsets.UTF_8);
        String sha = ChecksumUtil.sha256(data);

        assertThat(sha).hasSize(64);
        assertThat(ChecksumUtil.matches(sha, sha.toUpperCase())).isTrue();
    }

    @Test
    void rejectsCorruptedData(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("bundle.jar");
        Files.writeString(file, "valid-content");
        String originalSha = ChecksumUtil.sha256(file);

        Files.writeString(file, "corrupted-content");
        String corruptedSha = ChecksumUtil.sha256(file);

        assertThat(ChecksumUtil.matches(corruptedSha, originalSha)).isFalse();
    }
}
