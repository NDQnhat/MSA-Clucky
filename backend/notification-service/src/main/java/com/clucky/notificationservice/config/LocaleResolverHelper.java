package com.clucky.notificationservice.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Locale;

public final class LocaleResolverHelper {
    private LocaleResolverHelper() {}

    public static String resolve(String langParam, String acceptLanguage) {
        if (langParam != null && !langParam.isBlank()) {
            String normalized = langParam.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("en")) return "en";
            if (normalized.startsWith("vi")) return "vi";
        }
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            for (String token : acceptLanguage.split(",")) {
                String tag = token.split(";")[0].trim().toLowerCase(Locale.ROOT);
                if (tag.startsWith("en")) return "en";
                if (tag.startsWith("vi")) return "vi";
            }
        }
        return "vi";
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) return "vi";
        return resolve(request.getParameter("lang"), request.getHeader("Accept-Language"));
    }

    public static String currentRequestLang() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return resolve(servletAttrs.getRequest());
        }
        return "vi";
    }
}
