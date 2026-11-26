package com.clucky.promotionloyaltyservice.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.io.Serializable;
import java.text.Normalizer;
import java.util.Locale;

@JsonDeserialize(using = LocalizedText.Deserializer.class)
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

    public boolean hasText() {
        return (vi != null && !vi.isBlank()) || (en != null && !en.isBlank());
    }

    public boolean hasVi() {
        return vi != null && !vi.isBlank();
    }

    public boolean matches(String query) {
        if (query == null || query.isBlank()) return true;
        String needle = normalizeForSearch(query);
        return containsNormalized(vi, needle) || containsNormalized(en, needle);
    }

    private static boolean containsNormalized(String value, String needle) {
        if (value == null || value.isBlank()) return false;
        return normalizeForSearch(value).contains(needle);
    }

    public static String normalizeForSearch(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'd');
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public static String resolve(LocalizedText text, String lang) {
        return text == null ? "" : text.resolve(lang);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static class Deserializer extends JsonDeserializer<LocalizedText> {
        @Override
        public LocalizedText deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_NULL) return null;
            if (token == JsonToken.VALUE_STRING) return LocalizedText.ofSingle(parser.getValueAsString());
            JsonNode node = parser.readValueAsTree();
            if (node == null || node.isNull()) return null;
            if (node.isTextual()) return LocalizedText.ofSingle(node.asText());
            if (!node.isObject()) return null;
            String vi = node.has("vi") && !node.get("vi").isNull() ? node.get("vi").asText() : null;
            String en = node.has("en") && !node.get("en").isNull() ? node.get("en").asText() : null;
            String cleanVi = trimToNull(vi);
            String cleanEn = trimToNull(en);
            if (cleanVi == null && cleanEn == null) return new LocalizedText("", "");
            return new LocalizedText(cleanVi != null ? cleanVi : "", cleanEn != null ? cleanEn : "");
        }
    }
}
