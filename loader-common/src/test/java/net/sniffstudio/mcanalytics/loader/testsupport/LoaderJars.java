package net.sniffstudio.mcanalytics.loader.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds small jars that carry the plugin descriptor of the real loader. */
public final class LoaderJars {

    private LoaderJars() {}

    public static byte[] paper(String version, String padding) {
        return zip("plugin.yml", "name: MCAnalyticsLoader\nversion: " + version
                + "\nmain: net.sniffstudio.mcanalytics.loader.paper.PaperLoaderPlugin\n", padding);
    }

    public static byte[] velocity(String version, String padding) {
        return zip("velocity-plugin.json", "{\"id\":\"mcanalytics-loader\",\"name\":\"MCAnalytics Loader\",\"version\":\""
                + version + "\"}", padding);
    }

    /** A jar without a loader descriptor, such as a connector bundle. */
    public static byte[] connector(String padding) {
        return zip("connector.txt", "connector", padding);
    }

    public static byte[] zip(String descriptorName, String descriptor, String padding) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry(descriptorName));
                zip.write(descriptor.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("padding.txt"));
                zip.write(padding.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
