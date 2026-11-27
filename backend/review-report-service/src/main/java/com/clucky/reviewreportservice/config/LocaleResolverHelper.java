package com.clucky.reviewreportservice.config;

import java.util.Locale;

public final class LocaleResolverHelper {
    private LocaleResolverHelper() {}

    public static String resolve(String langParam, String acceptLanguage) {
        if (langParam != null && !langParam.isBlank()) {
            return normalize(langParam);
        }
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            String primary = acceptLanguage.split(",")[0].trim();
            return normalize(primary);
        }
        return "vi";
    }

    private static String normalize(String raw) {
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("en")) return "en";
        return "vi";
    }
}
