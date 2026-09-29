package net.sniffstudio.mcanalytics.loader.update;

import net.sniffstudio.mcanalytics.loader.net.ReleaseClient.ReleaseMetadata;
import net.sniffstudio.mcanalytics.loader.testsupport.LoaderJars;
import net.sniffstudio.mcanalytics.loader.testsupport.SigningFixture;
import net.sniffstudio.mcanalytics.loader.util.ChecksumUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LoaderUpdaterTest {

    private static final String PAPER_JAR = "mcanalytics-loader-paper-1.0.4.jar";
    private static final String VELOCITY_JAR = "mcanalytics-loader-velocity-1.0.4.jar";

    private final SigningFixture signing = new SigningFixture();

    private Path plugins(Path root, String jarName) throws IOException {
        Path plugins = root.resolve("plugins");
        Files.createDirectories(plugins);
        Files.write(plugins.resolve(jarName), new byte[]{1, 2, 3});
        return plugins;
    }

    private LoaderUpdater updater(String platform, String current, Path plugins, String jarName, Path updateFolder) {
        return new LoaderUpdater(platform, current, plugins.resolve(jarName), updateFolder, signing.verifier());
    }

    private ReleaseMetadata meta(String platform, String version, byte[] jar) {
        return new ReleaseMetadata(platform, version, ChecksumUtil.sha256(jar), jar.length, "/x", "1.0.0", signing.sign(jar));
    }

    private Path download(Path root, byte[] jar) throws IOException {
        Path file = root.resolve("download.tmp");
        Files.write(file, jar);
        return file;
    }

    private List<String> names(Path folder) throws IOException {
        try (Stream<Path> s = Files.list(folder)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    // ---- Paper --------------------------------------------------------------------------

    @Test
    @DisplayName("Paper: the jar is staged in plugins/update under the exact name of the running jar")
    void paperStagesInUpdateFolderUnderTheSameName(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, PAPER_JAR);
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        LoaderUpdater updater = updater("paper", "1.0.4", plugins, PAPER_JAR, null);

        LoaderUpdater.StageResult result = updater.stage(download(root, jar), meta("paper", "1.0.5", jar));

        assertThat(result.outcome()).isEqualTo(LoaderUpdater.Outcome.STAGED);
        Path staged = plugins.resolve("update").resolve(PAPER_JAR);
        assertThat(updater.stagedPath()).isEqualTo(staged.toAbsolutePath().normalize());
        assertThat(staged.getFileName().toString()).isEqualTo(plugins.resolve(PAPER_JAR).getFileName().toString());
        assertThat(Files.readAllBytes(staged)).isEqualTo(jar);
        // The running jar is not touched and no temporary file is left behind.
        assertThat(Files.readAllBytes(plugins.resolve(PAPER_JAR))).isEqualTo(new byte[]{1, 2, 3});
        assertThat(names(plugins.resolve("update"))).containsExactly(PAPER_JAR);
        assertThat(names(plugins)).containsExactlyInAnyOrder(PAPER_JAR, "update");
        assertThat(updater.stagedVersion()).contains("1.0.5");
    }

    @Test
    @DisplayName("Paper: a server that moved its update folder inside plugins is followed")
    void paperFollowsAnUpdateFolderInsidePlugins(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, PAPER_JAR);
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        LoaderUpdater updater = updater("paper", "1.0.4", plugins, PAPER_JAR, plugins.resolve("staging"));

        assertThat(updater.stage(download(root, jar), meta("paper", "1.0.5", jar)).outcome()).isEqualTo(LoaderUpdater.Outcome.STAGED);
        assertThat(Files.isRegularFile(plugins.resolve("staging").resolve(PAPER_JAR))).isTrue();
    }

    @Test
    @DisplayName("Never writes outside the plugins folder: an update folder elsewhere, or plugins itself, is refused")
    void refusesAnUpdateFolderOutsideThePluginsFolder(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, PAPER_JAR);
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        Path outside = root.resolve("elsewhere");

        for (Path folder : new Path[]{outside, plugins, plugins.resolve("..").resolve("elsewhere"), plugins.getParent()}) {
            LoaderUpdater updater = updater("paper", "1.0.4", plugins, PAPER_JAR, folder);
            LoaderUpdater.StageResult result = updater.stage(download(root, jar), meta("paper", "1.0.5", jar));
            assertThat(result.outcome()).as(folder.toString()).isEqualTo(LoaderUpdater.Outcome.REFUSED);
        }
        assertThat(Files.exists(outside)).isFalse();
        assertThat(Files.readAllBytes(plugins.resolve(PAPER_JAR))).isEqualTo(new byte[]{1, 2, 3});
        assertThat(names(plugins)).containsExactly(PAPER_JAR);
    }

    @Test
    @DisplayName("An update folder that is a symbolic link out of plugins is refused")
    void refusesAnUpdateFolderThatIsASymlinkOut(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, PAPER_JAR);
        Path outside = Files.createDirectories(root.resolve("elsewhere"));
        try {
            Files.createSymbolicLink(plugins.resolve("update"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // No symbolic links on this file system.
        }
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        LoaderUpdater updater = updater("paper", "1.0.4", plugins, PAPER_JAR, null);

        assertThat(updater.stage(download(root, jar), meta("paper", "1.0.5", jar)).outcome()).isEqualTo(LoaderUpdater.Outcome.REFUSED);
        assertThat(names(outside)).isEmpty();
    }

    @Test
    @DisplayName("Refuses a jar whose signature does not verify, and stages nothing")
    void refusesABadSignature(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, PAPER_JAR);
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        byte[] other = LoaderJars.paper("1.0.5", "other bytes");
        // The signature belongs to different bytes.
        ReleaseMetadata forged = new ReleaseMetadata("paper", "1.0.5", ChecksumUtil.sha256(jar), jar.length, "/x", "1.0.0", signing.sign(other));
        // A signature from a key the loader does not trust.
        ReleaseMetadata untrusted = new ReleaseMetadata("paper", "1.0.5", ChecksumUtil.sha256(jar), jar.length, "/x", "1.0.0",
                SigningFixture.sign(SigningFixture.newKeyPair(), jar));
        LoaderUpdater updater = updater("paper", "1.0.4", plugins, PAPER_JAR, null);

        for (ReleaseMetadata bad : new ReleaseMetadata[]{forged, untrusted}) {
            LoaderUpdater.StageResult result = updater.stage(download(root, jar), bad);
            assertThat(result.outcome()).isEqualTo(LoaderUpdater.Outcome.REFUSED);
            assertThat(result.detail()).contains("signature");
        }
        assertThat(Files.exists(plugins.resolve("update"))).isFalse();
    }

    @Test
    @DisplayName("Refuses a wrong checksum or size")
    void refusesAWrongChecksumOrSize(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, PAPER_JAR);
        byte[] jar = LoaderJars.paper("1.0.5", "new");
        LoaderUpdater updater = updater("paper", "1.0.4", plugins, PAPER_JAR, null);
        ReleaseMetadata good = meta("paper", "1.0.5", jar);

        ReleaseMetadata badSha = new ReleaseMetadata("paper", "1.0.5", "0".repeat(64), jar.length, "/x", "1.0.0", good.signature());
        ReleaseMetadata badSize = new ReleaseMetadata("paper", "1.0.5", good.sha256(), jar.length + 1, "/x", "1.0.0", good.signature());

        assertThat(updater.stage(download(root, jar), badSha).outcome()).isEqualTo(LoaderUpdater.Outcome.REFUSED);
        assertThat(updater.stage(download(root, jar), badSize).outcome()).isEqualTo(LoaderUpdater.Outcome.REFUSED);
        assertThat(Files.exists(plugins.resolve("update"))).isFalse();
    }

    @Test
    @DisplayName("Refuses a validly signed jar that is not the loader, or that names another version")
    void refusesASignedJarThatIsNotTheAnnouncedLoader(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, PAPER_JAR);
        LoaderUpdater updater = updater("paper", "1.0.4", plugins, PAPER_JAR, null);

        byte[] connector = LoaderJars.connector("connector bytes");
        assertThat(updater.stage(download(root, connector), meta("paper", "1.0.5", connector)).outcome())
                .isEqualTo(LoaderUpdater.Outcome.REFUSED);

        byte[] velocityLoader = LoaderJars.velocity("1.0.5", "v");
        assertThat(updater.stage(download(root, velocityLoader), meta("paper", "1.0.5", velocityLoader)).outcome())
                .isEqualTo(LoaderUpdater.Outcome.REFUSED);

        // An old signed loader offered under a higher number.
        byte[] oldLoader = LoaderJars.paper("1.0.3", "old");
        LoaderUpdater.StageResult replay = updater.stage(download(root, oldLoader), meta("paper", "1.0.9", oldLoader));
        assertThat(replay.outcome()).isEqualTo(LoaderUpdater.Outcome.REFUSED);
        assertThat(replay.detail()).contains("1.0.3");

        assertThat(Files.exists(plugins.resolve("update"))).isFalse();
    }

    @Test
    @DisplayName("Never downgrades: the same or an older version is not staged")
    void neverStagesTheSameOrAnOlderVersion(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, PAPER_JAR);
        LoaderUpdater updater = updater("paper", "1.0.5", plugins, PAPER_JAR, null);

        for (String version : new String[]{"1.0.5", "1.0.4", "0.9.9", "1.0.0"}) {
            byte[] jar = LoaderJars.paper(version, "x");
            LoaderUpdater.StageResult result = updater.stage(download(root, jar), meta("paper", version, jar));
            assertThat(result.outcome()).as(version).isEqualTo(LoaderUpdater.Outcome.NOT_NEWER);
        }
        assertThat(Files.exists(plugins.resolve("update"))).isFalse();
    }

    @Test
    @DisplayName("A newer jar already staged is not replaced by an older one, and the same jar is not rewritten")
    void keepsTheNewestStagedJar(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, PAPER_JAR);
        LoaderUpdater updater = updater("paper", "1.0.4", plugins, PAPER_JAR, null);

        byte[] v6 = LoaderJars.paper("1.0.6", "six");
        byte[] v5 = LoaderJars.paper("1.0.5", "five");
        assertThat(updater.stage(download(root, v6), meta("paper", "1.0.6", v6)).outcome()).isEqualTo(LoaderUpdater.Outcome.STAGED);
        assertThat(updater.stage(download(root, v6), meta("paper", "1.0.6", v6)).outcome()).isEqualTo(LoaderUpdater.Outcome.ALREADY_STAGED);
        assertThat(updater.stage(download(root, v5), meta("paper", "1.0.5", v5)).outcome()).isEqualTo(LoaderUpdater.Outcome.ALREADY_STAGED);
        assertThat(Files.readAllBytes(plugins.resolve("update").resolve(PAPER_JAR))).isEqualTo(v6);

        // A still newer release replaces it.
        byte[] v7 = LoaderJars.paper("1.0.7", "seven");
        assertThat(updater.stage(download(root, v7), meta("paper", "1.0.7", v7)).outcome()).isEqualTo(LoaderUpdater.Outcome.STAGED);
        assertThat(Files.readAllBytes(plugins.resolve("update").resolve(PAPER_JAR))).isEqualTo(v7);
    }

    // ---- Velocity -----------------------------------------------------------------------

    @Test
    @DisplayName("Velocity: the jar is written as <jar>.pending next to the running jar, which Velocity does not load")
    void velocityStagesAPendingFile(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, VELOCITY_JAR);
        byte[] jar = LoaderJars.velocity("1.0.5", "new");
        LoaderUpdater updater = updater("velocity", "1.0.4", plugins, VELOCITY_JAR, null);

        assertThat(updater.stage(download(root, jar), meta("velocity", "1.0.5", jar)).outcome()).isEqualTo(LoaderUpdater.Outcome.STAGED);

        assertThat(names(plugins)).containsExactlyInAnyOrder(VELOCITY_JAR, VELOCITY_JAR + ".pending", VELOCITY_JAR + ".pending.verify.json");
        assertThat(names(plugins).stream().filter(n -> n.endsWith(".jar"))).containsExactly(VELOCITY_JAR);
        assertThat(Files.readAllBytes(plugins.resolve(VELOCITY_JAR))).isEqualTo(new byte[]{1, 2, 3});
        assertThat(updater.stagedVersion()).contains("1.0.5");
        assertThat(updater.stage(download(root, jar), meta("velocity", "1.0.5", jar)).outcome()).isEqualTo(LoaderUpdater.Outcome.ALREADY_STAGED);
    }

    @Test
    @DisplayName("Velocity: applying swaps the pending jar over the running jar's name, leaving exactly one loader")
    void velocityApplyLeavesExactlyOneLoader(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, VELOCITY_JAR);
        byte[] jar = LoaderJars.velocity("1.0.5", "new");
        LoaderUpdater updater = updater("velocity", "1.0.4", plugins, VELOCITY_JAR, null);
        updater.stage(download(root, jar), meta("velocity", "1.0.5", jar));

        LoaderUpdater.ApplyResult result = updater.applyPending();

        assertThat(result.outcome()).isEqualTo(LoaderUpdater.ApplyOutcome.APPLIED);
        assertThat(result.detail()).isEqualTo("1.0.5");
        assertThat(names(plugins)).containsExactly(VELOCITY_JAR);
        assertThat(Files.readAllBytes(plugins.resolve(VELOCITY_JAR))).isEqualTo(jar);
        assertThat(updater.applyPending().outcome()).isEqualTo(LoaderUpdater.ApplyOutcome.NONE);
    }

    @Test
    @DisplayName("Velocity: a pending jar changed on disk after staging is deleted, not installed")
    void velocityApplyRefusesATamperedPendingJar(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, VELOCITY_JAR);
        byte[] jar = LoaderJars.velocity("1.0.5", "new");
        LoaderUpdater updater = updater("velocity", "1.0.4", plugins, VELOCITY_JAR, null);
        updater.stage(download(root, jar), meta("velocity", "1.0.5", jar));
        Files.write(plugins.resolve(VELOCITY_JAR + ".pending"), LoaderJars.velocity("1.0.5", "evil"));

        LoaderUpdater.ApplyResult result = updater.applyPending();

        assertThat(result.outcome()).isEqualTo(LoaderUpdater.ApplyOutcome.DISCARDED);
        assertThat(names(plugins)).containsExactly(VELOCITY_JAR);
        assertThat(Files.readAllBytes(plugins.resolve(VELOCITY_JAR))).isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    @DisplayName("Velocity: a pending file without its record is deleted")
    void velocityApplyDeletesAPendingFileWithoutARecord(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, VELOCITY_JAR);
        Files.write(plugins.resolve(VELOCITY_JAR + ".pending"), LoaderJars.velocity("1.0.5", "new"));
        LoaderUpdater updater = updater("velocity", "1.0.4", plugins, VELOCITY_JAR, null);

        assertThat(updater.applyPending().outcome()).isEqualTo(LoaderUpdater.ApplyOutcome.DISCARDED);
        assertThat(names(plugins)).containsExactly(VELOCITY_JAR);
    }

    @Test
    @DisplayName("Velocity: a pending jar that is no longer newer than the running loader is deleted, never swapped in")
    void velocityApplyNeverDowngrades(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, VELOCITY_JAR);
        byte[] jar = LoaderJars.velocity("1.0.5", "new");
        updater("velocity", "1.0.4", plugins, VELOCITY_JAR, null).stage(download(root, jar), meta("velocity", "1.0.5", jar));

        // The operator meanwhile installed loader 1.0.6 by hand.
        LoaderUpdater running106 = updater("velocity", "1.0.6", plugins, VELOCITY_JAR, null);
        LoaderUpdater.ApplyResult result = running106.applyPending();

        assertThat(result.outcome()).isEqualTo(LoaderUpdater.ApplyOutcome.DISCARDED);
        assertThat(result.detail()).contains("not newer");
        assertThat(Files.readAllBytes(plugins.resolve(VELOCITY_JAR))).isEqualTo(new byte[]{1, 2, 3});
        assertThat(names(plugins)).containsExactly(VELOCITY_JAR);
    }

    @Test
    @DisplayName("Velocity: if the running jar was moved away, nothing is written under its old name")
    void velocityApplyDoesNothingWhenTheRunningJarIsGone(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, VELOCITY_JAR);
        byte[] jar = LoaderJars.velocity("1.0.5", "new");
        LoaderUpdater updater = updater("velocity", "1.0.4", plugins, VELOCITY_JAR, null);
        updater.stage(download(root, jar), meta("velocity", "1.0.5", jar));
        Files.delete(plugins.resolve(VELOCITY_JAR));

        assertThat(updater.applyPending().outcome()).isEqualTo(LoaderUpdater.ApplyOutcome.FAILED);
        assertThat(names(plugins).stream().filter(n -> n.endsWith(".jar"))).isEmpty();
    }

    @Test
    @DisplayName("Velocity: discardPending removes the pending jar and its record")
    void velocityDiscard(@TempDir Path root) throws Exception {
        Path plugins = plugins(root, VELOCITY_JAR);
        byte[] jar = LoaderJars.velocity("1.0.5", "new");
        LoaderUpdater updater = updater("velocity", "1.0.4", plugins, VELOCITY_JAR, null);
        updater.stage(download(root, jar), meta("velocity", "1.0.5", jar));

        assertThat(updater.discardPending()).isTrue();
        assertThat(updater.discardPending()).isFalse();
        assertThat(names(plugins)).containsExactly(VELOCITY_JAR);
    }

    // ---- Locator ------------------------------------------------------------------------

    @Test
    @DisplayName("The locator accepts a plain regular jar file and nothing else")
    void locatorAcceptsOnlyAPlainJarFile(@TempDir Path root) throws Exception {
        Path jar = Files.write(root.resolve(PAPER_JAR), new byte[]{1});
        assertThat(LoaderJarLocator.validate(jar)).contains(jar.toAbsolutePath().normalize());

        assertThat(LoaderJarLocator.validate(root)).as("a folder").isEmpty();
        assertThat(LoaderJarLocator.validate(root.resolve("missing.jar"))).as("missing").isEmpty();
        assertThat(LoaderJarLocator.validate(Files.write(root.resolve("loader.zip"), new byte[]{1}))).as("not a jar").isEmpty();
        assertThat(LoaderJarLocator.validate(Files.write(root.resolve("odd name;rm.jar"), new byte[]{1}))).as("odd name").isEmpty();
        try {
            Path link = Files.createSymbolicLink(root.resolve("link.jar"), jar);
            assertThat(LoaderJarLocator.validate(link)).as("symbolic link").isEmpty();
        } catch (UnsupportedOperationException | IOException ignored) {
            // No symbolic links on this file system.
        }
    }

    @Test
    @DisplayName("The locator reads the code source: a class in a folder or a boot class gives no jar")
    void locatorReadsTheCodeSource() {
        assertThat(LoaderJarLocator.locate(LoaderUpdater.class)).as("loaded from a classes folder in this build").isEmpty();
        assertThat(LoaderJarLocator.locate(String.class)).as("boot class").isEmpty();
        assertThat(LoaderJarLocator.locate(org.junit.jupiter.api.Test.class)).as("class from a jar").isPresent()
                .get().satisfies(p -> assertThat(p.getFileName().toString()).endsWith(".jar"));
    }
}
