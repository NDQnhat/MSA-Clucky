package com.clucky.catalogstoreservice.config;

import com.clucky.catalogstoreservice.controller.InternalCatalogController;
import com.clucky.catalogstoreservice.dto.ValidationDto;
import com.clucky.catalogstoreservice.service.ValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalCatalogController.class, properties = {
        "spring.config.name=internal-catalog-test",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
        "clucky.security.jwt.issuer=test", "clucky.security.jwt.audience=test"
})
@Import(SecurityConfig.class)
class InternalCatalogSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean ValidationService validation;
    @MockitoBean com.clucky.catalogstoreservice.service.OrderStockService orderStockService;

    @Test
    void onlyInternalServiceOrAdminCanValidateItems() throws Exception {
        String path = "/api/catalog/internal/validate-items";
        String body = "{\"storeId\":1,\"items\":[{\"productId\":1,\"quantity\":1}]}";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        when(decoder.decode("customer")).thenReturn(token("CUSTOMER"));
        mvc.perform(post(path).header("Authorization", "Bearer customer")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(validation);
        when(validation.validateOrderItems(any())).thenReturn(new ValidationDto.Response());
        for (String role : new String[]{"INTERNAL_SERVICE", "ADMIN"}) {
            when(decoder.decode(role)).thenReturn(token(role));
            mvc.perform(post(path).header("Authorization", "Bearer " + role)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        }
        verify(validation, times(2)).validateOrderItems(any());
    }

    @Test
    void onlyCartOrderServiceMayReserveOrReleaseStock() throws Exception {
        String reserve = "/api/catalog/internal/reserve-stock";
        String release = "/api/catalog/internal/release-stock";
        String reserveBody = "{\"orderId\":11,\"storeId\":2,\"stockQuantities\":{\"3\":1}}";
        String releaseBody = "{\"orderId\":11,\"storeId\":2}";

        when(decoder.decode("admin")).thenReturn(token("ADMIN"));
        when(decoder.decode("other-service")).thenReturn(token("INTERNAL_SERVICE", "payment-service"));
        when(decoder.decode("cart-service")).thenReturn(token("INTERNAL_SERVICE", "cart-order-service"));

        for (String path : new String[]{reserve, release}) {
            String body = path.equals(reserve) ? reserveBody : releaseBody;
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
            for (String forbidden : new String[]{"admin", "other-service"}) {
                mvc.perform(post(path).header("Authorization", "Bearer " + forbidden)
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isForbidden());
            }
            mvc.perform(post(path).header("Authorization", "Bearer cart-service")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }
        verify(orderStockService).reserveOrderStock(eq(11L), eq(2L), eq(java.util.Map.of(3L, 1)), any(), isNull());
        verify(orderStockService).releaseOrderStock(11L, 2L, null);
    }

    @Test
    void rejectsNonPositiveStockAtHttpBoundary() throws Exception {
        when(decoder.decode("cart-service")).thenReturn(token("INTERNAL_SERVICE", "cart-order-service"));
        mvc.perform(post("/api/catalog/internal/reserve-stock")
                        .header("Authorization", "Bearer cart-service")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":11,\"storeId\":2,\"stockQuantities\":{\"3\":-2}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orderStockService);
    }

    private Jwt token(String role) {
        return token(role, null);
    }

    private Jwt token(String role, String service) {
        var builder = Jwt.withTokenValue(role).header("alg", "HS256").subject("1").claim("role", role);
        if (service != null) builder.claim("service", service);
        return builder.build();
    }
}
