package net.sniffstudio.mcanalytics.loader.lifecycle;

import net.sniffstudio.mcanalytics.loader.api.ConnectorEntrypoint;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class ConnectorClassLoader extends URLClassLoader {

    private final Path bundleJar;

    public ConnectorClassLoader(Path bundleJar, ClassLoader parent) throws IOException {
        super(new URL[]{bundleJar.toUri().toURL()}, parent);
        this.bundleJar = bundleJar;
    }

    public Path getBundleJar() {
        return bundleJar;
    }

    /*
     * sqlite-jdbc binds its JNI methods by the class name org.sqlite.core.NativeDB, so the
     * connector bundle keeps org.sqlite.core (and the callback types) under their real names
     * while it relocates the rest of the driver. Parent-first lookup then lets another plugin's
     * unrelocated sqlite-jdbc win: Velocity's plugin class loader searches every plugin jar, so
     * NativeDB came from that copy and its constructor expects org.sqlite.SQLiteConfig rather
     * than the bundle's relocated one, which failed with NoSuchMethodError when the spool opened.
     * Classes and resources the bundle ships under org.sqlite are therefore looked up in the
     * bundle first. When the bundle has none (the Paper connector uses the server's copy) the
     * lookup falls back to the parent exactly as before.
     */
    private static final String[] CHILD_FIRST_CLASS_PREFIXES = {"org.sqlite."};
    private static final String[] CHILD_FIRST_RESOURCE_PREFIXES = {"org/sqlite/"};

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!startsWithAny(name, CHILD_FIRST_CLASS_PREFIXES)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException notInBundle) {
                    return super.loadClass(name, resolve);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    @Override
    public URL getResource(String name) {
        if (startsWithAny(name, CHILD_FIRST_RESOURCE_PREFIXES)) {
            URL own = findResource(name);
            if (own != null) {
                return own;
            }
        }
        return super.getResource(name);
    }

    private static boolean startsWithAny(String name, String[] prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public ConnectorEntrypoint loadEntrypoint(String platform) throws Exception {
        String className = null;

        try (JarFile jar = new JarFile(bundleJar.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                Attributes attrs = manifest.getMainAttributes();
                className = attrs.getValue("Connector-Entrypoint");
            }
        } catch (Exception ignored) {}

        if (className != null && !className.isBlank()) {
            Class<?> clazz = loadClass(className.trim());
            return (ConnectorEntrypoint) clazz.getDeclaredConstructor().newInstance();
        }

        String wellKnown = switch (platform.toLowerCase()) {
            case "velocity" -> "net.sniffstudio.mcanalytics.connector.velocity.VelocityConnectorEntrypoint";
            case "paper" -> "net.sniffstudio.mcanalytics.connector.paper.PaperConnectorEntrypoint";
            default -> null;
        };

        if (wellKnown != null) {
            try {
                Class<?> clazz = loadClass(wellKnown);
                return (ConnectorEntrypoint) clazz.getDeclaredConstructor().newInstance();
            } catch (ClassNotFoundException ignored) {}
        }

        ServiceLoader<ConnectorEntrypoint> loader = ServiceLoader.load(ConnectorEntrypoint.class, this);
        Iterator<ConnectorEntrypoint> it = loader.iterator();
        if (it.hasNext()) {
            return it.next();
        }

        throw new IllegalStateException("Could not find ConnectorEntrypoint in bundle: " + bundleJar.getFileName());
    }
}
