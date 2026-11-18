package com.clucky.catalogstoreservice.service;

import com.clucky.catalogstoreservice.entity.Product;
import com.clucky.catalogstoreservice.entity.Store;
import com.clucky.catalogstoreservice.entity.StoreInventory;
import com.clucky.catalogstoreservice.exception.ApiException;
import com.clucky.catalogstoreservice.exception.ErrorCode;
import com.clucky.catalogstoreservice.repository.ComboItemRepository;
import com.clucky.catalogstoreservice.repository.LowStockEpisodeRepository;
import com.clucky.catalogstoreservice.repository.ProductRepository;
import com.clucky.catalogstoreservice.repository.StoreInventoryRepository;
import com.clucky.catalogstoreservice.repository.StoreProductRepository;
import com.clucky.catalogstoreservice.repository.StoreRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompetingOrderStockReservationTest {

    @Test
    void twoDistinctOrdersCompetingForLastUnitOnlyReserveOnce() throws Exception {
        // The real repository must ask the database for a write row lock.
        Lock annotation = StoreInventoryRepository.class
                .getMethod("lockByStoreAndProduct", Long.class, Long.class).getAnnotation(Lock.class);
        assertNotNull(annotation);
        assertEquals(LockModeType.PESSIMISTIC_WRITE, annotation.value());
        assertNotNull(OrderStockService.class.getMethod("reserveOrderStock", long.class, long.class,
                Map.class, Map.class, String.class).getAnnotation(Transactional.class),
                "Ledger and inventory row locks must remain held in one transaction");

        StoreInventoryRepository inventories = mock(StoreInventoryRepository.class);
        StoreProductRepository storeProducts = mock(StoreProductRepository.class);
        LowStockEpisodeRepository episodes = mock(LowStockEpisodeRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Store store = new Store();
        store.setId(2L);
        store.setLowStockThreshold(0);
        Product product = new Product();
        product.setId(3L);

        AtomicInteger remaining = new AtomicInteger(1);
        ReentrantLock simulatedDatabaseRowLock = new ReentrantLock(true);
        // Simulate SELECT ... FOR UPDATE: each requester reads the remaining quantity
        // after the prior request commits, rather than sharing a stale snapshot.
        when(inventories.lockByStoreAndProduct(2L, 3L)).thenAnswer(invocation -> {
            simulatedDatabaseRowLock.lock();
            return Optional.of(new StoreInventory(null, store, product, remaining.get(), 0, null));
        });
        when(inventories.saveAndFlush(any(StoreInventory.class))).thenAnswer(invocation -> {
            StoreInventory saved = invocation.getArgument(0);
            remaining.set(saved.getQuantityAvailable());
            return saved;
        });
        when(storeProducts.findByStoreIdAndProductId(2L, 3L)).thenReturn(Optional.empty());
        when(episodes.findByStoreIdAndProductIdAndStatus(2L, 3L, "OPEN")).thenReturn(Optional.empty());
        when(jdbc.queryForMap(startsWith("SELECT store_id,status,quantities::text"), anyLong()))
                .thenReturn(Map.of("store_id", 2L, "status", "PENDING", "quantities", "{}"));

        InventoryService inventory = new InventoryService(inventories, storeProducts,
                mock(StoreRepository.class), mock(ProductRepository.class), episodes,
                mock(LowStockAlertPublisher.class), mock(CatalogCacheService.class), mock(CatalogEvents.class));
        OrderStockService orders = new OrderStockService(jdbc, inventory, mock(ComboItemRepository.class), new ObjectMapper());

        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> orderOne = attempt(orders, 1001L, start, simulatedDatabaseRowLock);
        Callable<Boolean> orderTwo = attempt(orders, 1002L, start, simulatedDatabaseRowLock);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(orderOne);
            var second = pool.submit(orderTwo);
            start.countDown();
            boolean firstReserved = first.get(5, TimeUnit.SECONDS);
            boolean secondReserved = second.get(5, TimeUnit.SECONDS);

            assertNotEquals(firstReserved, secondReserved, "Two different orders cannot consume the same last unit");
            assertEquals(0, remaining.get());
            verify(inventories, times(2)).lockByStoreAndProduct(2L, 3L);
            verify(inventories, times(1)).saveAndFlush(any(StoreInventory.class));
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Boolean> attempt(OrderStockService orders, long orderId, CountDownLatch start,
                                      ReentrantLock simulatedDatabaseRowLock) {
        return () -> {
            start.await();
            try {
                orders.reserveOrderStock(orderId, 2L, Map.of(3L, 1), Map.of(), "order-" + orderId);
                return true;
            } catch (ApiException insufficient) {
                assertEquals(ErrorCode.INSUFFICIENT_INVENTORY, insufficient.getCode());
                return false;
            } finally {
                // Simulate transaction completion releasing the database row lock.
                if (simulatedDatabaseRowLock.isHeldByCurrentThread()) simulatedDatabaseRowLock.unlock();
            }
        };
    }
}
