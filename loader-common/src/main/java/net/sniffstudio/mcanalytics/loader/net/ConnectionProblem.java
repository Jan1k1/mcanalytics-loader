package net.sniffstudio.mcanalytics.loader.net;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * Plain words for why mcanalytics.org could not be reached, for console lines an operator reads.
 *
 * <p>A raw exception message or response body is never part of the result: an edge in front of
 * the site answers a deploy with bodies like {@code error code: 502} plus a newline, which read
 * like a crash in a server console.
 */
public final class ConnectionProblem {

    public static final String PUBLIC_HOST = "mcanalytics.org";
    public static final String DISCORD_URL = "https://discord.gg/9MWENuGmYn";

    private ConnectionProblem() {
    }

    /**
     * @return "timed out", "address lookup failed" or "connection failed"
     */
    public static String describe(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current instanceof UnknownHostException) {
                return "address lookup failed";
            }
            if (current instanceof HttpTimeoutException
                    || current instanceof HttpConnectTimeoutException
                    || current instanceof SocketTimeoutException) {
                return "timed out";
            }
            if (current instanceof ConnectException || current instanceof NoRouteToHostException) {
                // The JDK client wraps a failed lookup in a ConnectException, so keep walking.
                Throwable cause = current.getCause();
                if (cause instanceof UnknownHostException) {
                    return "address lookup failed";
                }
            }
            current = current.getCause();
            depth++;
        }
        return "connection failed";
    }

    /**
     * @return "HTTP 502" style text, or "connection failed" for a request that got no answer
     */
    public static String describeStatus(int statusCode) {
        return statusCode > 0 ? "HTTP " + statusCode : "connection failed";
    }

    /**
     * True for an answer that says the site is down, busy or unreachable rather than that this
     * server did something wrong: no answer at all, a timeout, a rate limit or a server error.
     */
    public static boolean isConnectionStatus(int statusCode) {
        return statusCode <= 0
                || statusCode == 403
                || statusCode == 408
                || statusCode == 429
                || (statusCode >= 500 && statusCode <= 599);
    }
}
