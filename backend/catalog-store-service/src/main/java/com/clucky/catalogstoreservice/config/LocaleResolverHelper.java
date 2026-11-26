package com.clucky.catalogstoreservice.config;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

/**
 * Resolves the active UI/API language ("vi" or "en") from explicit query parameter,
 * HTTP Accept-Language header, or current servlet request, defaulting to "vi".
 */
public final class LocaleResolverHelper {

    public static final String DEFAULT_LANG = "vi";

    private LocaleResolverHelper() {}

    public static String normalize(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        String lower = candidate.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("en")) return "en";
        if (lower.startsWith("vi")) return "vi";
        return null;
    }

    public static String resolve(String queryLang, String acceptLanguage) {
        String explicit = normalize(queryLang);
        if (explicit != null) return explicit;
        String header = fromAcceptLanguage(acceptLanguage);
        if (header != null) return header;
        return currentRequestLang();
    }

    public static String currentRequestLang() {
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttrs) {
            var request = servletAttrs.getRequest();
            String queryLang = normalize(request.getParameter("lang"));
            if (queryLang != null) return queryLang;
            String headerLang = fromAcceptLanguage(request.getHeader("Accept-Language"));
            if (headerLang != null) return headerLang;
        }
        return DEFAULT_LANG;
    }

    private static String fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) return null;
        for (String part : header.split(",")) {
            String tag = part.split(";")[0].trim();
            String normalized = normalize(tag);
            if (normalized != null) return normalized;
        }
        return null;
    }
}
