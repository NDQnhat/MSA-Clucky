package com.clucky.reviewreportservice.service;

import com.clucky.reviewreportservice.client.OrderServiceClient;
import com.clucky.reviewreportservice.entity.OrderProjectionTracker;
import com.clucky.reviewreportservice.repository.DailyItemStatsRepository;
import com.clucky.reviewreportservice.repository.DailyStoreStatsRepository;
import com.clucky.reviewreportservice.repository.OrderProjectionTrackerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ItemRevenueBackfillService {
    private static final Logger log = LoggerFactory.getLogger(ItemRevenueBackfillService.class);
    private static final int BATCH_SIZE = 200;

    private final OrderServiceClient orderServiceClient;
    private final OrderProjectionTrackerRepository trackerRepository;
    private final DailyStoreStatsRepository storeStatsRepository;
    private final DailyItemStatsRepository itemStatsRepository;
    private final ReportProjectionService projectionService;
    private final TransactionTemplate transactionTemplate;
    private final String revenueEventPolicy;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ItemRevenueBackfillService(
            OrderServiceClient orderServiceClient,
            OrderProjectionTrackerRepository trackerRepository,
            DailyStoreStatsRepository storeStatsRepository,
            DailyItemStatsRepository itemStatsRepository,
            ReportProjectionService projectionService,
            PlatformTransactionManager transactionManager,
            @Value("${clucky.reports.revenue-event:false}") String revenueEventPolicy
    ) {
        this.orderServiceClient = orderServiceClient;
        this.trackerRepository = trackerRepository;
        this.storeStatsRepository = storeStatsRepository;
        this.itemStatsRepository = itemStatsRepository;
        this.projectionService = projectionService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.revenueEventPolicy = revenueEventPolicy;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void triggerInitialBackfillIfEmpty() {
        if ("false".equalsIgnoreCase(revenueEventPolicy)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                if (itemStatsRepository.count() > 0) {
                    return;
                }
            } catch (Exception ex) {
                return;
            }
            for (int attempt = 1; attempt <= 4; attempt++) {
                try {
                    Thread.sleep(8000L * attempt);
                    int processed = runBackfill();
                    log.info("event=initial_item_revenue_backfill_done attempt={} processedOrders={}", attempt, processed);
                    return;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception ex) {
                    log.warn("event=initial_item_revenue_backfill_retry attempt={} error={}", attempt, ex.getMessage());
                }
            }
        });
    }

    public int runBackfill() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        int totalProcessed = 0;
        try {
            long afterOrderId = 0L;
            while (true) {
                List<OrderServiceClient.BackfillOrderDto> batch =
                        orderServiceClient.getCompletedOrdersForBackfill(afterOrderId, BATCH_SIZE);
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                for (OrderServiceClient.BackfillOrderDto dto : batch) {
                    if (dto.orderId() != null) {
                        afterOrderId = Math.max(afterOrderId, dto.orderId());
                        Boolean processed = transactionTemplate.execute(status -> backfillSingleOrder(dto));
                        if (Boolean.TRUE.equals(processed)) {
                            totalProcessed++;
                        }
                    }
                }
                if (batch.size() < BATCH_SIZE) {
                    break;
                }
            }
            return totalProcessed;
        } finally {
            running.set(false);
        }
    }

    private boolean backfillSingleOrder(OrderServiceClient.BackfillOrderDto dto) {
        if (dto.orderId() == null || dto.storeId() == null || dto.storeId() <= 0) {
            return false;
        }
        Instant completedAt = dto.completedAt() != null ? dto.completedAt() : Instant.now();
        LocalDate statDate = completedAt.atZone(ReportProjectionService.VIETNAM_ZONE).toLocalDate();

        trackerRepository.initialize(dto.orderId(), statDate);
        OrderProjectionTracker tracker = trackerRepository.findByOrderId(dto.orderId()).orElseThrow();
        if (tracker.isItemsContributed()) {
            return false;
        }
        if (tracker.getStoreId() == 0) {
            tracker.setStoreId(dto.storeId());
        }
        if (tracker.getOrderCode() == null) {
            tracker.setOrderCode(dto.orderCode());
        }

        if (!tracker.isContributed()) {
            BigDecimal total = nonNull(dto.totalAmount());
            String method = normalizePaymentMethod(dto);
            BigDecimal cod = dto.collectedCash() != null
                    ? dto.collectedCash()
                    : (Set.of("COD", "CASH").contains(method) ? total : BigDecimal.ZERO);
            BigDecimal transfer = dto.declaredTransfer() != null
                    ? dto.declaredTransfer()
                    : ("BANK_TRANSFER".equals(method) ? total : BigDecimal.ZERO);

            tracker.setStatDate(statDate);
            tracker.setPaymentMethod(method);
            tracker.setRevenueAmount(total);
            tracker.setCodAmount(cod);
            tracker.setTransferAmount(transfer);
            tracker.setOrderCompleted(true);
            tracker.setContributed(true);
            if ("BANK_TRANSFER".equals(method) || transfer.signum() > 0) {
                tracker.setPendingReconciliationAmount(transfer);
            }

            storeStatsRepository.upsertStatsDelta(
                    tracker.getStoreId(), tracker.getStatDate(), 1, total, cod, transfer);
            if (transfer.signum() > 0) {
                storeStatsRepository.upsertReconciliationDelta(
                        tracker.getStoreId(), tracker.getStatDate(),
                        BigDecimal.ZERO, transfer, BigDecimal.ZERO, BigDecimal.ZERO);
            }
        }

        List<ReportProjectionService.ItemLineInput> itemInputs = dto.items() == null ? List.of()
                : dto.items().stream()
                .map(i -> {
                    Long refId = "COMBO".equalsIgnoreCase(i.itemType()) ? i.comboId() : i.productId();
                    return new ReportProjectionService.ItemLineInput(
                            i.itemType(),
                            refId,
                            i.itemName(),
                            i.quantity() != null ? i.quantity() : 0,
                            nonNull(i.lineTotal())
                    );
                })
                .toList();

        projectionService.projectOrderBreakdownAndItems(
                tracker,
                nonNull(dto.discountAmount()),
                nonNull(dto.pointsAmount()),
                dto.orderSource(),
                dto.type(),
                itemInputs
        );
        tracker.setUpdatedAt(Instant.now());
        return true;
    }

    private String normalizePaymentMethod(OrderServiceClient.BackfillOrderDto dto) {
        String method = dto.collectionMethod() != null && !dto.collectionMethod().isBlank()
                ? dto.collectionMethod()
                : dto.paymentMethod();
        if (method == null || method.isBlank()) return "COD";
        String upper = method.trim().toUpperCase();
        if ("SPLIT".equals(upper)) return "MIXED";
        if ("TRANSFER".equals(upper)) return "BANK_TRANSFER";
        return upper;
    }

    private BigDecimal nonNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
