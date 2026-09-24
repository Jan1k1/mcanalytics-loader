package net.sniffstudio.mcanalytics.loader.lifecycle;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Velocity connector bundle relocates sqlite-jdbc but keeps org.sqlite.core under its real
 * name, because the JNI entry points are bound by that name. These tests build a "parent" jar
 * that plays another plugin's unrelocated sqlite-jdbc and a "bundle" jar that plays the
 * connector, then check which copy each lookup reaches.
 */
class ConnectorClassLoaderTest {

    /** Another plugin's unrelocated driver: its NativeDB takes org.sqlite.SQLiteConfig. */
    private static final Map<String, String> PARENT_SOURCES = Map.of(
            "org/sqlite/SQLiteConfig.java",
            "package org.sqlite; public class SQLiteConfig {}",
            "org/sqlite/core/NativeDB.java",
            "package org.sqlite.core; public class NativeDB {"
                    + " public NativeDB(org.sqlite.SQLiteConfig config) {}"
                    + " public String origin() { return \"parent\"; } }",
            "org/sqlite/OnlyInParent.java",
            "package org.sqlite; public class OnlyInParent {}",
            "demo/Shared.java",
            "package demo; public class Shared { public static String origin() { return \"parent\"; } }");

    /**
     * The connector: NativeDB keeps its name but takes the relocated SQLiteConfig, and the
     * relocated half calls that constructor the way SQLiteConnection.open does.
     */
    private static final Map<String, String> BUNDLE_SOURCES = Map.of(
            "relocated/sqlite/SQLiteConfig.java",
            "package relocated.sqlite; public class SQLiteConfig {}",
            "org/sqlite/core/NativeDB.java",
            "package org.sqlite.core; public class NativeDB {"
                    + " public NativeDB(relocated.sqlite.SQLiteConfig config) {}"
                    + " public String origin() { return \"bundle\"; } }",
            "relocated/sqlite/Opener.java",
            "package relocated.sqlite; public class Opener {"
                    + " public static String open() {"
                    + " return new org.sqlite.core.NativeDB(new SQLiteConfig()).origin(); } }",
            "demo/Shared.java",
            "package demo; public class Shared { public static String origin() { return \"bundle\"; } }");

    @TempDir
    static Path workDir;

    private static Path parentJar;
    private static Path bundleJar;

    @BeforeAll
    static void buildFixtureJars() throws IOException {
        parentJar = buildJar("parent", PARENT_SOURCES, Map.of(
                "org/sqlite/marker.txt", "parent",
                "org/sqlite/only-parent.txt", "parent",
                "demo/marker.txt", "parent"));
        bundleJar = buildJar("bundle", BUNDLE_SOURCES, Map.of(
                "org/sqlite/marker.txt", "bundle",
                "demo/marker.txt", "bundle"));
    }

    @Test
    @DisplayName("The relocated driver opens the bundle's NativeDB even when the parent can see another copy")
    void opensTheBundlesOwnNativeDb() throws Exception {
        URLClassLoader parent = parentLoader();
        try (parent; ConnectorClassLoader loader = new ConnectorClassLoader(bundleJar, parent)) {
            Class<?> opener = loader.loadClass("relocated.sqlite.Opener");

            // Parent first, this call died with NoSuchMethodError: NativeDB came from the
            // parent and had no constructor taking the relocated SQLiteConfig.
            assertThat(opener.getMethod("open").invoke(null)).isEqualTo("bundle");
            assertThat(loader.loadClass("org.sqlite.core.NativeDB").getClassLoader()).isSameAs(loader);
        }
    }

    @Test
    @DisplayName("Loading an org.sqlite class again returns the class defined the first time")
    void repeatedLookupsReturnTheSameClass() throws Exception {
        URLClassLoader parent = parentLoader();
        try (parent; ConnectorClassLoader loader = new ConnectorClassLoader(bundleJar, parent)) {
            Class<?> first = loader.loadClass("org.sqlite.core.NativeDB");

            assertThat(loader.loadClass("org.sqlite.core.NativeDB")).isSameAs(first);
            assertThat(Class.forName("org.sqlite.core.NativeDB", true, loader)).isSameAs(first);
        }
    }

    @Test
    @DisplayName("Classes outside org.sqlite keep resolving parent first")
    void otherClassesStayParentFirst() throws Exception {
        URLClassLoader parent = parentLoader();
        try (parent; ConnectorClassLoader loader = new ConnectorClassLoader(bundleJar, parent)) {
            Class<?> shared = loader.loadClass("demo.Shared");

            assertThat(shared.getClassLoader()).isSameAs(parent);
            assertThat(shared.getMethod("origin").invoke(null)).isEqualTo("parent");
            assertThat(loader.loadClass("relocated.sqlite.Opener").getClassLoader()).isSameAs(loader);
        }
    }

    @Test
    @DisplayName("An org.sqlite class the bundle does not ship still comes from the parent")
    void sqliteClassesMissingFromTheBundleFallBackToTheParent() throws Exception {
        URLClassLoader parent = parentLoader();
        try (parent; ConnectorClassLoader loader = new ConnectorClassLoader(bundleJar, parent)) {
            // The Paper bundle ships no sqlite-jdbc and relies on the server's copy.
            assertThat(loader.loadClass("org.sqlite.OnlyInParent").getClassLoader()).isSameAs(parent);
            assertThatThrownBy(() -> loader.loadClass("org.sqlite.Missing"))
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    @DisplayName("Resources under org/sqlite come from the bundle first, everything else from the parent")
    void sqliteResourcesComeFromTheBundleFirst() throws Exception {
        URLClassLoader parent = parentLoader();
        try (parent; ConnectorClassLoader loader = new ConnectorClassLoader(bundleJar, parent)) {
            assertThat(read(loader.getResource("org/sqlite/marker.txt"))).isEqualTo("bundle");
            try (InputStream in = loader.getResourceAsStream("org/sqlite/marker.txt")) {
                assertThat(in).isNotNull();
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("bundle");
            }
            assertThat(read(loader.getResource("org/sqlite/only-parent.txt"))).isEqualTo("parent");
            assertThat(read(loader.getResource("demo/marker.txt"))).isEqualTo("parent");
        }
    }

    private static URLClassLoader parentLoader() throws IOException {
        // No parent of its own beyond the bootstrap loader, like a plugin jar the proxy exposes.
        return new URLClassLoader(new URL[]{parentJar.toUri().toURL()}, null);
    }

    private static String read(URL url) throws IOException {
        assertThat(url).isNotNull();
        URLConnection connection = url.openConnection();
        // An uncached connection, so reading does not keep the fixture jar open.
        connection.setUseCaches(false);
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path buildJar(String name, Map<String, String> sources, Map<String, String> resources)
            throws IOException {
        Path sourceDir = workDir.resolve(name + "-src");
        Path classesDir = workDir.resolve(name + "-classes");
        Files.createDirectories(classesDir);

        List<String> args = new ArrayList<>(List.of("--release", "17", "-proc:none", "-d", classesDir.toString()));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = sourceDir.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK compiler for the fixture classes").isNotNull();
        assertThat(compiler.run(null, null, null, args.toArray(String[]::new)))
                .as("compiling the %s fixture", name)
                .isZero();

        for (Map.Entry<String, String> resource : resources.entrySet()) {
            Path file = classesDir.resolve(resource.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, resource.getValue());
        }

        List<Path> entries;
        try (Stream<Path> walk = Files.walk(classesDir)) {
            entries = walk.filter(Files::isRegularFile).sorted().toList();
        }
        Path jar = workDir.resolve(name + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Path entry : entries) {
                out.putNextEntry(new JarEntry(classesDir.relativize(entry).toString().replace('\\', '/')));
                Files.copy(entry, out);
                out.closeEntry();
            }
        }
        return jar;
    }
}
