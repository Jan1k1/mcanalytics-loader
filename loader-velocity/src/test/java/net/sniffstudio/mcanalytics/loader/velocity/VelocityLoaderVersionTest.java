package net.sniffstudio.mcanalytics.loader.velocity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityLoaderVersionTest {

    @Test
    @DisplayName("The plugin version matches the version Gradle builds")
    void pluginVersionMatchesBuildVersion() {
        assertThat(VelocityLoaderPlugin.VERSION).isEqualTo(System.getProperty("loader.version"));
    }
}
