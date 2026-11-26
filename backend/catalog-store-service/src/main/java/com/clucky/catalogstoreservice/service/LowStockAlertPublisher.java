package com.clucky.catalogstoreservice.service;

import com.clucky.catalogstoreservice.dto.LocalizedText;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class LowStockAlertPublisher {
    private final CatalogEvents events;
    private final ObjectMapper mapper;
    private final BranchProvisioning branches;

    public LowStockAlertPublisher(CatalogEvents events, ObjectMapper mapper, BranchProvisioning branches) {
        this.events = events;
        this.mapper = mapper;
        this.branches = branches;
    }

    public void publishLowStockAlert(Long storeId, Long productId, String productName, int quantity, int threshold, String episodeId) {
        publishLowStockAlert(storeId, productId, LocalizedText.ofSingle(productName), quantity, threshold, episodeId);
    }

    public void publishLowStockAlert(Long storeId, Long productId, LocalizedText productName, int quantity, int threshold, String episodeId) {
        String resolvedVi = productName != null && productName.hasText() ? productName.resolve("vi") : "Sản phẩm";
        var payload = mapper.createObjectNode()
                .put("storeId", storeId)
                .put("productId", productId)
                .put("productName", resolvedVi)
                .put("quantity", quantity)
                .put("threshold", threshold)
                .put("episodeId", episodeId);
        if (productName != null && productName.hasText()) {
            payload.set("productNameI18n", mapper.valueToTree(productName));
        }
        Long managerId = branches.managerUserId(storeId);
        if (managerId != null) payload.put("managerUserId", managerId);
        events.enqueue("inventory.low-stock", storeId.toString(), payload);
    }
}
