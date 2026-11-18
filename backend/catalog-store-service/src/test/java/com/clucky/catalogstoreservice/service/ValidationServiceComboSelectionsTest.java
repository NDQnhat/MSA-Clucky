package com.clucky.catalogstoreservice.service;

import com.clucky.catalogstoreservice.dto.ValidationDto;
import com.clucky.catalogstoreservice.entity.*;
import com.clucky.catalogstoreservice.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ValidationServiceComboSelectionsTest {
    private final StoreRepository stores = mock(StoreRepository.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductOptionRepository options = mock(ProductOptionRepository.class);
    private final ProductToppingRepository toppings = mock(ProductToppingRepository.class);
    private final ComboRepository combos = mock(ComboRepository.class);
    private final StoreProductRepository storeProducts = mock(StoreProductRepository.class);
    private final StoreInventoryRepository inventories = mock(StoreInventoryRepository.class);
    private final StoreService storeService = mock(StoreService.class);

    private ValidationService service;
    private Combo combo;
    private ComboItem comboItem;
    private Product product;
    private ProductOption fixedOption;
    private ProductTopping topping;

    @BeforeEach
    void setUp() {
        service = new ValidationService(stores, products, options, toppings, combos,
                storeProducts, inventories, storeService);

        Store store = new Store(1L, "Clucky", "Address", "0900000000",
                LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, StoreStatus.OPEN, null);
        store.setAccountReadiness("READY");
        when(stores.findById(1L)).thenReturn(Optional.of(store));
        when(storeService.isStoreServingNow(store)).thenReturn(true);

        product = new Product();
        product.setId(10L);
        product.setName("Chicken combo component");
        product.setIsActive(true);

        fixedOption = new ProductOption(13L, product, "Size", "Standard", BigDecimal.valueOf(9000), true);
        List<ProductOption> productOptions = List.of(
                new ProductOption(11L, product, "Spice", "Hot", BigDecimal.valueOf(5000), true),
                new ProductOption(12L, product, "Cheese", "Extra cheese", BigDecimal.valueOf(10000), true),
                fixedOption,
                new ProductOption(14L, product, "Size", "Large", BigDecimal.valueOf(20000), true)
        );
        when(options.findByProductId(10L)).thenReturn(productOptions);
        topping = new ProductTopping(21L, product, "Cheese sauce", BigDecimal.valueOf(10000));
        when(toppings.findByProductId(10L)).thenReturn(List.of(topping));

        combo = new Combo();
        combo.setId(5L);
        combo.setName("Combo test");
        combo.setComboPrice(BigDecimal.valueOf(120000));
        combo.setIsActive(true);
        comboItem = new ComboItem(100L, combo, product, 2, fixedOption.getId());
        combo.setItems(List.of(comboItem));
        when(combos.findByIdWithItems(5L)).thenReturn(Optional.of(combo));

        when(storeProducts.findByStoreIdAndProductId(1L, 10L))
                .thenReturn(Optional.of(new StoreProduct(1L, store, product, BigDecimal.valueOf(50000), true, false)));
        when(inventories.findByStoreIdAndProductId(1L, 10L))
                .thenReturn(Optional.of(new StoreInventory(1L, store, product, 20, 0, null)));
    }

    @Test
    void pricesDynamicOptionsByComponentQuantityAndDoesNotChargeFixedOption() {
        ValidationDto.Response response = validate(List.of(
                new ValidationDto.ComboSelectionRequest(100L, List.of(11L, 12L), List.of())
        ));

        assertTrue(response.isValid(), response.getMessage());
        assertEquals(BigDecimal.valueOf(150000), response.getSubtotal());
        ValidationDto.ValidatedItem item = response.getItems().getFirst();
        assertEquals(BigDecimal.valueOf(30000), item.getExtraPrice());
        assertEquals(BigDecimal.valueOf(150000), item.getLineTotal());
        assertEquals(3, item.getComboOptionsSnapshot().size());
        assertEquals(BigDecimal.valueOf(10000), item.getComboOptionsSnapshot().get(1).extraPrice());
        assertEquals(BigDecimal.valueOf(5000), item.getComboOptionsSnapshot().get(1).unitExtraPrice());
        ValidationDto.ComboOptionSnapshot fixed = item.getComboOptionsSnapshot().stream()
                .filter(ValidationDto.ComboOptionSnapshot::fixed).findFirst().orElseThrow();
        assertEquals(BigDecimal.ZERO, fixed.extraPrice());
        assertEquals(100L, fixed.comboItemId());
    }

    @Test
    void legacyComboWithoutSelectionsDoesNotRequireProductRequiredGroups() {
        ValidationDto.Response response = validate(List.of());

        assertTrue(response.isValid(), response.getMessage());
        assertEquals(BigDecimal.valueOf(120000), response.getSubtotal());
        ValidationDto.ValidatedItem item = response.getItems().getFirst();
        assertEquals(BigDecimal.ZERO, item.getExtraPrice());
        assertEquals(1, item.getComboOptionsSnapshot().size());
        assertTrue(item.getComboOptionsSnapshot().getFirst().fixed());
    }

    @Test
    void rejectsOptionThatBelongsToAnotherGroupAlreadyFixedInCombo() {
        ValidationDto.Response response = validate(List.of(
                new ValidationDto.ComboSelectionRequest(100L, List.of(14L), List.of())
        ));

        assertFalse(response.isValid());
        assertTrue(response.getMessage().contains("lựa chọn cố định"));
    }

    @Test
    void echoedFixedOptionIsIncludedOnceAndNeverChargedAsAnExtra() {
        ValidationDto.Response response = validate(List.of(
                new ValidationDto.ComboSelectionRequest(100L, List.of(13L), List.of())
        ));

        assertTrue(response.isValid(), response.getMessage());
        assertEquals(BigDecimal.valueOf(120000), response.getSubtotal());
        assertEquals(BigDecimal.ZERO, response.getItems().getFirst().getExtraPrice());
        assertEquals(1, response.getItems().getFirst().getComboOptionsSnapshot().size());
    }

    @Test
    void pricesToppingByComponentQuantityAndAllowsToppingWithoutRequiredOptions() {
        ValidationDto.Response response = validate(List.of(
                new ValidationDto.ComboSelectionRequest(100L, List.of(), List.of(21L))
        ));

        assertTrue(response.isValid(), response.getMessage());
        assertEquals(BigDecimal.valueOf(140000), response.getSubtotal());
        ValidationDto.ComboToppingSnapshot snapshot = response.getItems().getFirst()
                .getComboToppingsSnapshot().getFirst();
        assertEquals(BigDecimal.valueOf(20000), snapshot.extraPrice());
        assertEquals(BigDecimal.valueOf(10000), snapshot.unitExtraPrice());
        assertEquals(2, snapshot.componentQuantity());
    }

    @Test
    void rejectsOptionFromAnotherProduct() {
        ValidationDto.Response response = validate(List.of(
                new ValidationDto.ComboSelectionRequest(100L, List.of(999L), List.of())
        ));

        assertFalse(response.isValid());
        assertTrue(response.getMessage().contains("không thuộc món"));
    }

    private ValidationDto.Response validate(List<ValidationDto.ComboSelectionRequest> selections) {
        ValidationDto.ValidateItemRequest item = new ValidationDto.ValidateItemRequest();
        item.setComboId(5L);
        item.setQuantity(1);
        item.setComboSelections(selections);
        ValidationDto.Request request = new ValidationDto.Request();
        request.setStoreId(1L);
        request.setItems(List.of(item));
        return service.validateOrderItems(request);
    }
}
