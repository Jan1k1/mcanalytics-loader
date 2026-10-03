package net.sniffstudio.mcanalytics.loader.bungee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.sniffstudio.mcanalytics.loader.util.VersionUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BungeeDescriptorTest {

    private String read() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("bungee.yml")) {
            assertThat(in).as("bungee.yml is packaged").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("bungee.yml carries the version Gradle builds, the loader's name and its main class")
    void descriptor() throws Exception {
        String version = System.getProperty("loader.version");
        assertThat(read())
                .contains("version: " + version)
                .doesNotContain("${version}")
                .contains("name: MCAnalyticsLoader")
                .contains("main: net.sniffstudio.mcanalytics.loader.bungee.BungeeLoaderPlugin");
    }

    /**
     * The web app publishes the bungee bundle with minLoader 1.0.6, the first loader that knows the
     * platform. A bungee loader built as an older version warns every operator, on every start,
     * that it is too old and that they should download a newer loader that does not exist.
     */
    @Test
    @DisplayName("The bungee loader is at least 1.0.6, the floor the release API sets for the bungee bundle")
    void versionMeetsTheBungeeFloor() {
        String version = System.getProperty("loader.version");
        assertThat(VersionUtil.compare(version, "1.0.6")).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("The loader asks for the bungee bundle")
    void platformName() {
        assertThat(BungeeLoaderPlugin.PLATFORM).isEqualTo("bungee");
    }
}
