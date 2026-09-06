package net.sniffstudio.mcanalytics.loader.paper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PluginDescriptorTest {

    private String read(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " is packaged").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("Both plugin descriptors carry the version Gradle builds")
    void descriptorsCarryBuildVersion() throws Exception {
        String version = System.getProperty("loader.version");
        assertThat(version).isNotBlank();

        for (String resource : new String[]{"plugin.yml", "paper-plugin.yml"}) {
            assertThat(read(resource))
                    .contains("version: " + version)
                    .doesNotContain("${version}");
        }
    }

    @Test
    @DisplayName("Both plugin descriptors point at the loader main class")
    void descriptorsPointAtMainClass() throws Exception {
        for (String resource : new String[]{"plugin.yml", "paper-plugin.yml"}) {
            assertThat(read(resource)).contains("main: net.sniffstudio.mcanalytics.loader.paper.PaperLoaderPlugin");
        }
    }
}
