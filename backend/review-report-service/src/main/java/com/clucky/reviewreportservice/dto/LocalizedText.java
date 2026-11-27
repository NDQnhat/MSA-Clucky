package com.clucky.reviewreportservice.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.Serializable;

public record LocalizedText(String vi, String en) implements Serializable {

    public static LocalizedText of(String vi, String en) {
        String cleanVi = trimToNull(vi);
        String cleanEn = trimToNull(en);
        if (cleanVi == null && cleanEn == null) return null;
        return new LocalizedText(
                cleanVi != null ? cleanVi : cleanEn,
                cleanEn != null ? cleanEn : cleanVi
        );
    }

    public static LocalizedText ofSingle(String value) {
        String clean = trimToNull(value);
        return clean == null ? null : new LocalizedText(clean, clean);
    }

    public static LocalizedText fromJsonNode(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return ofSingle(node.asText());
        if (!node.isObject()) return null;
        String vi = node.hasNonNull("vi") ? node.get("vi").asText() : null;
        String en = node.hasNonNull("en") ? node.get("en").asText() : null;
        return of(vi, en);
    }

    public String resolve(String lang) {
        if ("en".equalsIgnoreCase(lang)) {
            if (en != null && !en.isBlank()) return en;
            if (vi != null && !vi.isBlank()) return vi;
            return "";
        }
        if (vi != null && !vi.isBlank()) return vi;
        if (en != null && !en.isBlank()) return en;
        return "";
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
