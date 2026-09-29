package net.sniffstudio.mcanalytics.loader.net;

import net.sniffstudio.mcanalytics.loader.config.LoaderCredentials;
import net.sniffstudio.mcanalytics.loader.util.BundleVerifier;
import net.sniffstudio.mcanalytics.loader.util.ChecksumUtil;
import net.sniffstudio.mcanalytics.loader.util.TinyJson;
import net.sniffstudio.mcanalytics.loader.util.VersionUtil;

import java.io.ByteArrayOutputStream;
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
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

public final class ReleaseClient {

    /** Largest JSON reply the loader reads. Real replies are a few hundred bytes. */
    public static final int MAX_JSON_BYTES = 64 * 1024;
    /** Largest connector jar the loader downloads, whatever the server declares. */
    public static final long MAX_BUNDLE_BYTES = 64L * 1024 * 1024;
    /** Status a {@link ReleaseCheckResult} carries when the loader refused the reply itself. */
    public static final int STATUS_REFUSED = -1;

    /** Where the loader asks for a newer loader jar. */
    public static final String LOADER_RELEASE_PATH = "/api/v1/connector/loader/release";

    private static final int MAX_REDIRECTS = 3;
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

    private final HttpClient httpClient;
    private final String userAgent;

    public ReleaseClient(String loaderVersion, String platform) {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                // Redirects are followed by hand, and only inside the same origin, so an
                // Authorization header can never be replayed to another host.
                .followRedirects(HttpClient.Redirect.NEVER)
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

    public record ReleaseMetadata(String platform, String version, String sha256, long sizeBytes, String downloadPath, String minLoader, String signature) {}

    /**
     * @param errorMessage for an unanswered request, plain words from {@link ConnectionProblem}
     *                     ("timed out", "connection failed", ...); never a raw exception message
     */
    public record ReleaseCheckResult(int statusCode, ReleaseMetadata metadata, String errorMessage) {}

    /**
     * The loader refused what the server sent: a checksum, size or limit that did not hold, or a
     * redirect to another host. The message is fixed text, never part of the reply.
     */
    public static class ReleaseRejectedException extends IOException {
        private static final long serialVersionUID = 1L;

        public ReleaseRejectedException(String message) {
            super(message);
        }
    }

    /** A download that got an answer other than 200. Carries only the status, never the body. */
    public static final class DownloadStatusException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int statusCode;

        public DownloadStatusException(int statusCode) {
            super("Download failed with HTTP status " + statusCode);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

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

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            String responseBody = readBody(response);

            if (status == 200 && responseBody != null) {
                Map<String, Object> json = TinyJson.parseObject(responseBody);
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

            if (status >= 500 && status <= 599) {
                return new PairResult(false, status, null, "Cannot reach " + ConnectionProblem.PUBLIC_HOST
                        + " right now (HTTP " + status + "). Your code was not used, so try again in a minute.");
            }

            String errorMsg = "Pairing failed (HTTP " + status + ")";
            try {
                Map<String, Object> json = TinyJson.parseObject(responseBody);
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
            return new PairResult(false, 0, null, "Cannot reach " + ConnectionProblem.PUBLIC_HOST + " right now ("
                    + ConnectionProblem.describe(e) + "). Your code was not used, so try again in a minute.");
        }
    }

    public ReleaseCheckResult checkRelease(String apiUrl, String platform, String token) {
        return fetchRelease(apiUrl, "/api/v1/connector/release", platform, token);
    }

    /**
     * Asks whether a newer loader jar is published. Same token, same reply shape and same
     * validation as {@link #checkRelease}; a 404 means no loader release is published.
     */
    public ReleaseCheckResult checkLoaderRelease(String apiUrl, String platform, String token) {
        return fetchRelease(apiUrl, LOADER_RELEASE_PATH, platform, token);
    }

    private ReleaseCheckResult fetchRelease(String apiUrl, String path, String platform, String token) {
        validateEndpointUrl(apiUrl);
        try {
            URI uri = URI.create(apiUrl + path + "?platform=" + platform);
            HttpResponse<InputStream> response = sendGet(uri, token, Duration.ofSeconds(15));
            int status = response.statusCode();
            String body = readBody(response);

            if (status == 200) {
                if (body == null) {
                    return new ReleaseCheckResult(status, null, "the release information was too large or unreadable");
                }
                Map<String, Object> data;
                try {
                    data = TinyJson.getObject(TinyJson.parseObject(body), "data");
                } catch (IllegalArgumentException e) {
                    return new ReleaseCheckResult(status, null, "the release information could not be read");
                }
                if (data != null) {
                    return parseRelease(status, platform, data);
                }
            }

            String errorMsg = "HTTP " + status;
            if (ConnectionProblem.isConnectionStatus(status)) {
                // An edge answers a deploy with a text body such as "error code: 502". The status
                // says everything an operator needs.
                return new ReleaseCheckResult(status, null, errorMsg);
            }
            try {
                Map<String, Object> json = TinyJson.parseObject(body);
                Map<String, Object> errObj = TinyJson.getObject(json, "error");
                if (errObj != null) {
                    String msg = TinyJson.getString(errObj, "message");
                    if (msg != null && !msg.isBlank()) {
                        errorMsg = msg;
                    }
                }
            } catch (Exception ignored) {}

            return new ReleaseCheckResult(status, null, errorMsg);
        } catch (ReleaseRejectedException e) {
            return new ReleaseCheckResult(STATUS_REFUSED, null, e.getMessage());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ReleaseCheckResult(0, null, ConnectionProblem.describe(e));
        }
    }

    /**
     * Turns the {@code data} object of a release reply into metadata, or refuses it. A release is
     * only usable with a strict version, a 64 character sha256, a size above zero and within the
     * download limit, and a download path; nothing is defaulted, because a default would switch a
     * check off.
     */
    static ReleaseCheckResult parseRelease(int status, String platform, Map<String, Object> data) {
        String ver = TinyJson.getString(data, "version");
        String sha = TinyJson.getString(data, "sha256");
        Long size = TinyJson.getLong(data, "sizeBytes");
        String path = TinyJson.getString(data, "downloadPath");
        String minLoader = TinyJson.getString(data, "minLoader");
        String signature = TinyJson.getString(data, "signature");

        String problem = null;
        if (!VersionUtil.isStrictVersion(ver)) {
            problem = "a missing or invalid version";
        } else if (sha == null || !SHA256_HEX.matcher(sha).matches()) {
            problem = "a missing or invalid sha256";
        } else if (size == null || size <= 0) {
            problem = "a missing or invalid size";
        } else if (size > MAX_BUNDLE_BYTES) {
            problem = "a size above the " + (MAX_BUNDLE_BYTES / (1024 * 1024)) + " MB limit";
        } else if (path == null || path.isBlank()) {
            problem = "a missing download path";
        } else if (!BundleVerifier.isWellFormedSignature(signature)) {
            problem = "a missing or malformed signature";
        }
        if (problem != null) {
            return new ReleaseCheckResult(status, null, "the release information was refused: it has " + problem);
        }
        return new ReleaseCheckResult(status, new ReleaseMetadata(
                platform,
                ver,
                sha.toLowerCase(java.util.Locale.ROOT),
                size,
                path,
                minLoader != null ? minLoader : "1.0.0",
                signature), null);
    }

    /**
     * Downloads the jar into {@code tempTarget} and checks it against the release information.
     * The file is deleted again unless every check passes.
     *
     * @param expectedSha256 the 64 hex character checksum from the release information
     * @param expectedSizeBytes the size from the release information; must be above zero
     * @throws ReleaseRejectedException when the checksum or size is missing or does not hold, or
     *         the server redirects to another host
     */
    public void downloadBundle(String downloadUrl, String token, Path tempTarget, String expectedSha256, long expectedSizeBytes) throws Exception {
        validateEndpointUrl(downloadUrl);
        if (expectedSha256 == null || !SHA256_HEX.matcher(expectedSha256).matches()) {
            throw new ReleaseRejectedException("Missing or invalid checksum: the download was not started");
        }
        if (expectedSizeBytes <= 0 || expectedSizeBytes > MAX_BUNDLE_BYTES) {
            throw new ReleaseRejectedException("Missing or invalid size: the download was not started");
        }

        HttpResponse<InputStream> response = sendGet(URI.create(downloadUrl), token, Duration.ofSeconds(60));
        if (response.statusCode() != 200) {
            try {
                // Read nothing, only release the connection.
                response.body().close();
            } catch (IOException ignored) {
            }
            throw new DownloadStatusException(response.statusCode());
        }

        boolean complete = false;
        try {
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > expectedSizeBytes) {
                response.body().close();
                throw new ReleaseRejectedException("Downloaded file size mismatch: the server announced more than the "
                        + expectedSizeBytes + " bytes it declared");
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long totalBytes = 0;

            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(tempTarget)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > expectedSizeBytes) {
                        // Never write past the declared size, so a hostile stream cannot fill the disk.
                        throw new ReleaseRejectedException("Downloaded file size mismatch: expected "
                                + expectedSizeBytes + " bytes, got more");
                    }
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
            }

            if (totalBytes != expectedSizeBytes) {
                throw new ReleaseRejectedException("Downloaded file size mismatch: expected " + expectedSizeBytes + " bytes, got " + totalBytes);
            }

            String actualSha256 = ChecksumUtil.sha256Hex(digest.digest());
            if (!ChecksumUtil.matches(actualSha256, expectedSha256)) {
                throw new ReleaseRejectedException("Checksum mismatch: expected " + expectedSha256 + ", got " + actualSha256);
            }
            complete = true;
        } finally {
            if (!complete) {
                Files.deleteIfExists(tempTarget);
            }
        }
    }

    /**
     * GET with the bearer token. A redirect is followed only when it stays on the origin of the
     * first request (scheme, host and port); anything else is refused before a second request
     * exists, so the token never reaches another host.
     */
    private HttpResponse<InputStream> sendGet(URI uri, String token, Duration timeout) throws IOException, InterruptedException {
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(current)
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + token)
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) {
                return response;
            }
            String location = response.headers().firstValue("Location").orElse(null);
            try {
                response.body().close();
            } catch (IOException ignored) {
            }
            URI next;
            try {
                next = location == null ? null : current.resolve(location);
            } catch (IllegalArgumentException e) {
                next = null;
            }
            if (next == null || !sameOrigin(uri, next)) {
                throw new ReleaseRejectedException("Refused a redirect to another host");
            }
            current = next;
        }
        throw new ReleaseRejectedException("Refused a chain of more than " + MAX_REDIRECTS + " redirects");
    }

    /** Same scheme, host and port, and no credentials in the address. */
    static boolean sameOrigin(URI base, URI other) {
        if (base == null || other == null || other.getUserInfo() != null) {
            return false;
        }
        return base.getScheme() != null && other.getScheme() != null
                && base.getScheme().equalsIgnoreCase(other.getScheme())
                && base.getHost() != null && other.getHost() != null
                && base.getHost().equalsIgnoreCase(other.getHost())
                && effectivePort(base) == effectivePort(other);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * Builds the download address from the configured API base and the path the server sent, or
     * refuses it. The path must start with exactly one {@code /}: {@code @evil.example/x} and
     * {@code //evil.example/x} would otherwise move the host. The result must have the scheme,
     * host and port of the base.
     *
     * @throws ReleaseRejectedException when the path would leave the API host
     */
    public static URI resolveDownloadUri(String apiBase, String downloadPath) throws ReleaseRejectedException {
        if (downloadPath == null || downloadPath.length() < 2 || downloadPath.charAt(0) != '/' || downloadPath.charAt(1) == '/') {
            throw new ReleaseRejectedException("Refused a download path that does not start with a single '/'");
        }
        URI base;
        URI resolved;
        try {
            base = URI.create(apiBase);
            resolved = base.resolve(downloadPath).normalize();
        } catch (IllegalArgumentException e) {
            throw new ReleaseRejectedException("Refused a download path that is not a valid address");
        }
        if (!sameOrigin(base, resolved)) {
            throw new ReleaseRejectedException("Refused a download address on another host");
        }
        return resolved;
    }

    /**
     * Reads at most {@link #MAX_JSON_BYTES} of a reply as UTF-8 text and closes it.
     *
     * @return the text, or null when the reply is larger than the limit or cannot be read
     */
    private static String readBody(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_JSON_BYTES) {
                    return null;
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
