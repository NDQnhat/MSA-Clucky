package com.clucky.cartorderservice.cart;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CartItemDtoComboSelectionKeyTest {
    @Test
    void comboSelectionChoicesArePartOfCartIdentityAndCanonicalized() {
        CartItemDto first = combo(List.of(
                new ComboSelectionDto(101L, List.of(11L, 12L), List.of(21L)),
                new ComboSelectionDto(102L, List.of(31L), List.of())
        ));
        CartItemDto sameChoicesDifferentOrder = combo(List.of(
                new ComboSelectionDto(102L, List.of(31L), List.of()),
                new ComboSelectionDto(101L, List.of(12L, 11L), List.of(21L))
        ));
        CartItemDto differentChoices = combo(List.of(
                new ComboSelectionDto(101L, List.of(13L, 12L), List.of(21L)),
                new ComboSelectionDto(102L, List.of(31L), List.of())
        ));
        CartItemDto legacyNoSelection = combo(List.of());
        CartItemDto explicitEmptyRow = combo(List.of(new ComboSelectionDto(101L, List.of(), List.of())));

        assertEquals(first.generateItemKey(), sameChoicesDifferentOrder.generateItemKey());
        assertNotEquals(first.generateItemKey(), differentChoices.generateItemKey());
        assertEquals(legacyNoSelection.generateItemKey(), explicitEmptyRow.generateItemKey());
    }

    private CartItemDto combo(List<ComboSelectionDto> selections) {
        CartItemDto item = new CartItemDto();
        item.setComboId(5L);
        item.setComboSelections(selections);
        return item;
    }
}
