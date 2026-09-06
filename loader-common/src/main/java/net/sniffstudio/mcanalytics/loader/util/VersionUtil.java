package net.sniffstudio.mcanalytics.loader.util;

public final class VersionUtil {

    private VersionUtil() {}

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
