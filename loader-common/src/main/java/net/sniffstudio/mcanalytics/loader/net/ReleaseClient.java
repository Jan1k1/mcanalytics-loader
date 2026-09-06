package net.sniffstudio.mcanalytics.loader.net;

import net.sniffstudio.mcanalytics.loader.config.LoaderCredentials;
import net.sniffstudio.mcanalytics.loader.util.ChecksumUtil;
import net.sniffstudio.mcanalytics.loader.util.TinyJson;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReleaseClient {

    private final HttpClient httpClient;
    private final String userAgent;

    public ReleaseClient(String loaderVersion, String platform) {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.userAgent = "MCAnalytics-Loader/" + loaderVersion + " (" + platform + ")";
    }

    public ReleaseClient(String loaderVersion, String platform, String endpointUrl) {
        this(loaderVersion, platform);
        if (endpointUrl != null) {
            validateEndpointUrl(endpointUrl);
        }
    }

    public static void validateEndpointUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Endpoint URL cannot be null or empty");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid endpoint URL: " + url, e);
        }
        validateEndpointUrl(uri);
    }

    public static void validateEndpointUrl(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("Endpoint URI cannot be null");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("MCAnalytics endpoint URL scheme must be http or https: " + uri);
        }
        if (scheme.equalsIgnoreCase("https")) {
            return;
        }
        String host = uri.getHost();
        if (host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1"))) {
            return;
        }
        throw new IllegalArgumentException("MCAnalytics endpoint URL must use HTTPS (unsecured HTTP is only allowed for localhost or 127.0.0.1): " + uri);
    }

    public record PairResult(boolean success, int statusCode, LoaderCredentials credentials, String errorMessage) {}

    public record ReleaseMetadata(String platform, String version, String sha256, long sizeBytes, String downloadPath, String minLoader) {}

    public record ReleaseCheckResult(int statusCode, ReleaseMetadata metadata, String errorMessage) {}

    public PairResult pair(String apiUrl, String code, String platform) {
        validateEndpointUrl(apiUrl);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", code.trim());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/v1/connector/pair"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", userAgent)
                    .POST(HttpRequest.BodyPublishers.ofString(TinyJson.toJson(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 200) {
                Map<String, Object> json = TinyJson.parseObject(response.body());
                Map<String, Object> data = TinyJson.getObject(json, "data");
                if (data != null) {
                    String token = TinyJson.getString(data, "connectorToken");
                    String networkId = TinyJson.getString(data, "networkId");
                    String serverId = TinyJson.getString(data, "serverId");
                    String serverName = TinyJson.getString(data, "serverName");
                    String respPlatform = TinyJson.getString(data, "platform");
                    if (respPlatform == null || respPlatform.isBlank()) {
                        respPlatform = platform;
                    }

                    LoaderCredentials creds = new LoaderCredentials(token, networkId, serverId, serverName, respPlatform, Instant.now());
                    return new PairResult(true, status, creds, null);
                }
            }

            String errorMsg = "Pairing failed (HTTP " + status + ")";
            try {
                Map<String, Object> json = TinyJson.parseObject(response.body());
                Map<String, Object> errObj = TinyJson.getObject(json, "error");
                if (errObj != null) {
                    String msg = TinyJson.getString(errObj, "message");
                    if (msg != null && !msg.isBlank()) {
                        errorMsg = msg;
                    }
                }
            } catch (Exception ignored) {}

            return new PairResult(false, status, null, errorMsg);
        } catch (Exception e) {
            return new PairResult(false, 0, null, "Could not reach MCAnalytics pairing service: " + e.getMessage());
        }
    }

    public ReleaseCheckResult checkRelease(String apiUrl, String platform, String token) {
        validateEndpointUrl(apiUrl);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/api/v1/connector/release?platform=" + platform))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 200) {
                Map<String, Object> json = TinyJson.parseObject(response.body());
                Map<String, Object> data = TinyJson.getObject(json, "data");
                if (data != null) {
                    String ver = TinyJson.getString(data, "version");
                    String sha = TinyJson.getString(data, "sha256");
                    Long size = TinyJson.getLong(data, "sizeBytes");
                    String path = TinyJson.getString(data, "downloadPath");
                    String minLoader = TinyJson.getString(data, "minLoader");

                    ReleaseMetadata meta = new ReleaseMetadata(
                            platform,
                            ver,
                            sha,
                            size != null ? size : 0L,
                            path,
                            minLoader != null ? minLoader : "1.0.0"
                    );
                    return new ReleaseCheckResult(status, meta, null);
                }
            }

            String errorMsg = "HTTP " + status;
            try {
                Map<String, Object> json = TinyJson.parseObject(response.body());
                Map<String, Object> errObj = TinyJson.getObject(json, "error");
                if (errObj != null) {
                    String msg = TinyJson.getString(errObj, "message");
                    if (msg != null && !msg.isBlank()) {
                        errorMsg = msg;
                    }
                }
            } catch (Exception ignored) {}

            return new ReleaseCheckResult(status, null, errorMsg);
        } catch (Exception e) {
            return new ReleaseCheckResult(0, null, e.getMessage());
        }
    }

    public void downloadBundle(String downloadUrl, String token, Path tempTarget, String expectedSha256, long expectedSizeBytes) throws Exception {
        validateEndpointUrl(downloadUrl);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", userAgent)
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("Download failed with HTTP status " + response.statusCode());
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long totalBytes = 0;

        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(tempTarget)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                totalBytes += read;
            }
        }

        if (expectedSizeBytes > 0 && totalBytes != expectedSizeBytes) {
            Files.deleteIfExists(tempTarget);
            throw new IOException("Downloaded file size mismatch: expected " + expectedSizeBytes + " bytes, got " + totalBytes);
        }

        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        String actualSha256 = sb.toString();

        if (expectedSha256 != null && !ChecksumUtil.matches(actualSha256, expectedSha256)) {
            Files.deleteIfExists(tempTarget);
            throw new IOException("Checksum mismatch: expected " + expectedSha256 + ", got " + actualSha256);
        }
    }
}
