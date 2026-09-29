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

    @Test
    void strictVersionAcceptsOnlyThreeShortNumbers() {
        assertThat(VersionUtil.isStrictVersion("1.2.3")).isTrue();
        assertThat(VersionUtil.isStrictVersion("0.0.0")).isTrue();
        assertThat(VersionUtil.isStrictVersion("1234.5678.9012")).isTrue();

        for (String bad : new String[]{null, "", "1.2", "1.2.3.4", "12345.1.1", "1.2.3-beta", "1.2.3\n", "1.2.3 ",
                " 1.2.3", "../1.2.3", "1.2.3/../../x", "1.2.\u0663", "a.b.c", "1..3", "1.2.3+build", "..\\..\\1.2.3"}) {
            assertThat(VersionUtil.isStrictVersion(bad)).as("version %s", bad).isFalse();
        }
    }

    @Test
    void isNewerComparesNumbersNotText() {
        assertThat(VersionUtil.isNewer("1.0.4", "1.0.5")).isTrue();
        assertThat(VersionUtil.isNewer("1.0.9", "1.0.10")).isTrue();
        assertThat(VersionUtil.isNewer("1.9.9", "1.10.0")).isTrue();
        assertThat(VersionUtil.isNewer("1.0.5", "2.0.0")).isTrue();
        assertThat(VersionUtil.isNewer("1.0.4-SNAPSHOT", "1.0.5")).isTrue();
    }

    @Test
    void isNewerRefusesSameOlderAndMalformed() {
        assertThat(VersionUtil.isNewer("1.0.5", "1.0.5")).isFalse();
        assertThat(VersionUtil.isNewer("1.0.5", "1.0.4")).isFalse();
        assertThat(VersionUtil.isNewer("1.10.0", "1.9.9")).isFalse();
        assertThat(VersionUtil.isNewer("1.0.5", null)).isFalse();
        assertThat(VersionUtil.isNewer("1.0.5", "1.0.6-beta")).isFalse();
        assertThat(VersionUtil.isNewer("1.0.5", "../1.0.6")).isFalse();
        assertThat(VersionUtil.isNewer("1.0.5", "9.9.9.9")).isFalse();
    }
}
