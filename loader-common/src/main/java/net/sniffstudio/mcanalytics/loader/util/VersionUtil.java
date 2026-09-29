package net.sniffstudio.mcanalytics.loader.util;

import java.util.regex.Pattern;

public final class VersionUtil {

    /**
     * The only shape of version the loader lets into a file name: three numbers of one to four
     * digits. Anything else, such as a path, a suffix or a newline, is refused.
     */
    private static final Pattern STRICT_VERSION = Pattern.compile("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}");

    private VersionUtil() {}

    /** True for {@code ^[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}$} and nothing else. */
    public static boolean isStrictVersion(String version) {
        return version != null && STRICT_VERSION.matcher(version).matches();
    }

    /**
     * True only when {@code candidate} is a strict {@code x.y.z} version that is higher than
     * {@code current}. Equal, lower and malformed candidates are all false, so a loader can
     * never be moved back to an older jar.
     */
    public static boolean isNewer(String current, String candidate) {
        return isStrictVersion(candidate) && compare(current, candidate) < 0;
    }

    public static int compare(String v1, String v2) {
        if (v1 == null && v2 == null) {
            return 0;
        }
        if (v1 == null) {
            return -1;
        }
        if (v2 == null) {
            return 1;
        }

        String clean1 = v1.trim().replaceAll("-[A-Za-z0-9_.]+", "").replaceAll("\\+[A-Za-z0-9_.]+", "");
        String clean2 = v2.trim().replaceAll("-[A-Za-z0-9_.]+", "").replaceAll("\\+[A-Za-z0-9_.]+", "");

        String[] parts1 = clean1.split("\\.");
        String[] parts2 = clean2.split("\\.");

        int length = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < length; i++) {
            int num1 = i < parts1.length ? parseNumber(parts1[i]) : 0;
            int num2 = i < parts2.length ? parseNumber(parts2[i]) : 0;

            if (num1 != num2) {
                return Integer.compare(num1, num2);
            }
        }

        return 0;
    }

    private static int parseNumber(String part) {
        if (part == null || part.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(part.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
