package com.clucky.cartorderservice.dto;

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

    public static LocalizedText fromJsonNode(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return ofSingle(node.asText());
        if (!node.isObject()) return null;
        String vi = node.has("vi") && !node.get("vi").isNull() ? node.get("vi").asText() : null;
        String en = node.has("en") && !node.get("en").isNull() ? node.get("en").asText() : null;
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

    public static String resolve(LocalizedText text, String lang) {
        return text == null ? "" : text.resolve(lang);
    }

    public static String resolveJsonNode(JsonNode node, String lang) {
        LocalizedText text = fromJsonNode(node);
        return text != null ? text.resolve(lang) : null;
    }

    public static JsonNode localizeSnapshotNode(JsonNode snapshot, String lang) {
        if (snapshot == null || snapshot.isNull() || !snapshot.isArray()) {
            return snapshot;
        }
        JsonNode copy = snapshot.deepCopy();
        for (JsonNode elem : copy) {
            if (elem instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
                localizeField(obj, "name", "nameI18n", lang);
                localizeField(obj, "groupName", "groupNameI18n", lang);
                localizeField(obj, "productName", "productNameI18n", lang);
            }
        }
        return copy;
    }

    private static void localizeField(com.fasterxml.jackson.databind.node.ObjectNode obj, String field, String i18nField, String lang) {
        if (obj.hasNonNull(i18nField)) {
            String resolved = resolveJsonNode(obj.get(i18nField), lang);
            if (resolved != null && !resolved.isBlank()) {
                obj.put(field, resolved);
            }
        }
    }

    public static String normalizeForSearch(String value) {
        if (value == null) return "";
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'd');
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
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
