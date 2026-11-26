package com.clucky.notificationservice.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.io.Serializable;

@JsonDeserialize(using = LocalizedText.Deserializer.class)
public record LocalizedText(String vi, String en) implements Serializable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    public static LocalizedText fromDbValue(Object raw) {
        if (raw == null) return null;
        String text = raw.toString().trim();
        if (text.isEmpty()) return null;
        if (text.startsWith("{") || text.startsWith("\"")) {
            try {
                JsonNode node = MAPPER.readTree(text);
                if (node.isTextual()) return ofSingle(node.asText());
                if (node.isObject()) {
                    String vi = node.has("vi") && !node.get("vi").isNull() ? node.get("vi").asText() : null;
                    String en = node.has("en") && !node.get("en").isNull() ? node.get("en").asText() : null;
                    return of(vi, en);
                }
            } catch (Exception ignored) {
                // Fall back to plain string below
            }
        }
        return ofSingle(text);
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception ex) {
            return "{\"vi\":\"\",\"en\":\"\"}";
        }
    }

    public static String toJson(String vi, String en) {
        LocalizedText value = of(vi, en);
        return value == null ? "{\"vi\":\"\",\"en\":\"\"}" : value.toJson();
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
