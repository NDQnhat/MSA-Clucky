package com.clucky.cartorderservice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.clucky.cartorderservice.cart.ComboSelectionDto;
import jakarta.validation.constraints.Min;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CounterOrderItemRequest(
        Long productId,
        Long comboId,
        @Min(value = 1, message = "Quantity must be greater than 0") Integer quantity,
        List<Long> optionIds,
        List<Long> toppingIds,
        List<Object> options,
        String note,
        List<ComboSelectionDto> comboSelections
) {
    public CounterOrderItemRequest(Long productId, Long comboId, Integer quantity, List<Long> optionIds, List<Long> toppingIds, List<Object> options, String note) {
        this(productId, comboId, quantity, optionIds, toppingIds, options, note, List.of());
    }

    public CounterOrderItemRequest {
        quantity = quantity != null && quantity > 0 ? quantity : 1;
        optionIds = optionIds != null ? optionIds : List.of();
        toppingIds = toppingIds != null ? toppingIds : List.of();
        options = options != null ? options : List.of();
        comboSelections = comboSelections != null ? comboSelections : List.of();
    }

    public List<Long> resolveOptionIds() {
        if (optionIds != null && !optionIds.isEmpty()) {
            return optionIds;
        }
        if (options != null && !options.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (Object opt : options) {
                if (opt instanceof Number n) {
                    ids.add(n.longValue());
                } else if (opt instanceof String s) {
                    String clean = s.replaceFirst("^(?i)opt-", "").trim();
                    if (clean.matches("\\d+")) {
                        ids.add(Long.parseLong(clean));
                    }
                } else if (opt instanceof Map<?, ?> m) {
                    Object raw = m.get("id") != null ? m.get("id") : m.get("choiceId");
                    if (raw instanceof Number n) {
                        ids.add(n.longValue());
                    } else if (raw instanceof String s) {
                        String clean = s.replaceFirst("^(?i)opt-", "").trim();
                        if (clean.matches("\\d+")) {
                            ids.add(Long.parseLong(clean));
                        }
                    }
                }
            }
            return ids;
        }
        return List.of();
    }
}
