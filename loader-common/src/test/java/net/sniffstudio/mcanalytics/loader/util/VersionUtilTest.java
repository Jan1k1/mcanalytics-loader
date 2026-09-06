package net.sniffstudio.mcanalytics.loader.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionUtilTest {

    @Test
    void comparesSemverVersions() {
        assertThat(VersionUtil.compare("1.0.0", "1.0.0")).isEqualTo(0);
        assertThat(VersionUtil.compare("1.0.0", "1.0.1")).isNegative();
        assertThat(VersionUtil.compare("1.1.0", "1.0.9")).isPositive();
        assertThat(VersionUtil.compare("2.0.0", "1.9.9")).isPositive();
        assertThat(VersionUtil.compare("1.0", "1.0.0")).isEqualTo(0);
        assertThat(VersionUtil.compare("1.0.0-SNAPSHOT", "1.0.0")).isEqualTo(0);
        assertThat(VersionUtil.compare("1.0.1-SNAPSHOT", "1.0.0")).isPositive();
    }

    @Test
    void respectsMinLoader() {
        String currentLoader = "1.0.0";
        String requiredMinLoaderOld = "0.9.0";
        String requiredMinLoaderEqual = "1.0.0";
        String requiredMinLoaderNewer = "1.1.0";

        assertThat(VersionUtil.compare(currentLoader, requiredMinLoaderOld)).isPositive();
        assertThat(VersionUtil.compare(currentLoader, requiredMinLoaderEqual)).isEqualTo(0);
        assertThat(VersionUtil.compare(currentLoader, requiredMinLoaderNewer)).isNegative();
    }
}
