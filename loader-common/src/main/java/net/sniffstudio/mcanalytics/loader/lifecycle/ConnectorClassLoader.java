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
