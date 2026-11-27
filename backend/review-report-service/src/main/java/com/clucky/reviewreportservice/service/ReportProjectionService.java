package com.clucky.reviewreportservice.service;

import com.clucky.reviewreportservice.dto.DomainEvent;
import com.clucky.reviewreportservice.dto.LocalizedText;
import com.clucky.reviewreportservice.entity.OrderProjectionTracker;
import com.clucky.reviewreportservice.repository.DailyItemStatsRepository;
import com.clucky.reviewreportservice.repository.DailyStoreStatsRepository;
import com.clucky.reviewreportservice.repository.OrderProjectionTrackerRepository;
import com.clucky.reviewreportservice.repository.ProcessedEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ReportProjectionService {
    public static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final BigDecimal MAX_VND = new BigDecimal("999999999999");
    private static final String EXPIRED_RECEIPT_STATUS = "EXPIRED_RECEIPT";
    private static final Set<String> RECONCILED_STATUSES = Set.of(
            "RECONCILED", "BANK_CONFIRMED", "CONFIRMED", "MATCHED", "SETTLED", "AUTO_MATCHED", "MANUAL_APPROVED");
    private static final Set<String> PENDING_RECONCILIATION_STATUSES = Set.of(
            "PENDING_RECONCILIATION", "MANUAL_ACCEPTED", "COUNTER_DECLARED", "UNRECONCILED", "PENDING");

    private final DailyStoreStatsRepository statsRepository;
    private final DailyItemStatsRepository itemStatsRepository;
    private final OrderProjectionTrackerRepository trackerRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;
    private final String revenueEvent;

    public ReportProjectionService(DailyStoreStatsRepository statsRepository,
            OrderProjectionTrackerRepository trackerRepository, ProcessedEventRepository processedEventRepository,
            @Value("${clucky.reports.revenue-event:false}") String revenueEvent) {
        this(statsRepository, null, trackerRepository, processedEventRepository, new ObjectMapper(), revenueEvent);
    }

    @Autowired
    public ReportProjectionService(DailyStoreStatsRepository statsRepository,
            DailyItemStatsRepository itemStatsRepository,
            OrderProjectionTrackerRepository trackerRepository, ProcessedEventRepository processedEventRepository,
            ObjectMapper objectMapper,
            @Value("${clucky.reports.revenue-event:false}") String revenueEvent) {
        if (!Set.of("false", "order.completed", "payment.confirmed").contains(revenueEvent)) {
            throw new IllegalArgumentException("Unsupported report revenue policy");
        }
        this.statsRepository = statsRepository;
        this.itemStatsRepository = itemStatsRepository;
        this.trackerRepository = trackerRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.revenueEvent = revenueEvent;
    }

    @Transactional
    public boolean processProjectionEvent(DomainEvent event) {
        requireEnabled();
        validateEvent(event);
        if (!Set.of("order.completed", "payment.confirmed").contains(event.eventType())) {
            throw new IllegalArgumentException("Unsupported projection event");
        }
        JsonNode payload = event.payload();
        long orderId = positiveId(payload, "orderId");
        Long storeId = payload.hasNonNull("storeId") ? positiveId(payload, "storeId") : null;
        boolean authoritative = revenueEvent.equals(event.eventType());
        LocalDate statDate = resolveStatDate(event, payload);

        // Validate optional financial/reconciliation fields before claiming the event
        BigDecimal explicitExcess = resolveOptionalAmount(payload, "excessAmount", "overpaidAmount");
        BigDecimal explicitExpired = resolveOptionalAmount(payload, "expiredReceiptAmount");
        BigDecimal explicitOrderTotal = resolveOptionalAmount(payload, "orderTotal");
        BigDecimal explicitPaidAmount = resolveOptionalAmount(payload, "paidAmount", "receivedAmount");
        BigDecimal explicitReconciledTransfer = resolveOptionalAmount(payload, "reconciledTransferAmount");
        BigDecimal explicitPendingReconciliation = resolveOptionalAmount(payload, "pendingReconciliationAmount");
        BigDecimal explicitCash = resolveOptionalAmount(payload, "cashAmount");
        // SePay-confirmed POS receipts report the verified bank portion under
        // bankAmount; Order snapshots continue to use declaredTransfer.
        BigDecimal explicitTransfer = resolveOptionalAmount(payload, "declaredTransfer", "bankAmount");
        boolean isExpiredOrder = payload.path("expired").asBoolean(false)
                || "EXPIRED".equalsIgnoreCase(payload.path("orderStatus").asText(""));
        String eventReconciliationStatus = resolveOptionalReconciliationStatus(event, payload, isExpiredOrder);

        BigDecimal rawAmount = authoritative ? resolveAmount(payload) : null;
        String method = authoritative ? resolvePaymentMethod(payload) : null;

        BigDecimal recognizedAmount = null;
        BigDecimal computedExcess = explicitExcess != null ? explicitExcess : BigDecimal.ZERO;
        BigDecimal computedExpired = explicitExpired != null ? explicitExpired : BigDecimal.ZERO;

        if (authoritative) {
            if (isExpiredOrder) {
                // Expired orders must not contribute to order_count or revenue; keep late receipts separated
                recognizedAmount = BigDecimal.ZERO;
                if (computedExpired.signum() == 0 && rawAmount.signum() > 0) {
                    computedExpired = rawAmount;
                }
            } else {
                BigDecimal baselineOrderTotal = explicitOrderTotal != null ? explicitOrderTotal : rawAmount;
                recognizedAmount = rawAmount.min(baselineOrderTotal);
                if (explicitExcess == null) {
                    BigDecimal paidOrRaw = explicitPaidAmount != null ? explicitPaidAmount : rawAmount;
                    if (paidOrRaw.compareTo(baselineOrderTotal) > 0) {
                        computedExcess = paidOrRaw.subtract(baselineOrderTotal);
                    }
                }
            }
        }

        if (processedEventRepository.claim(event.eventId(), event.eventType(), orderId) == 0) {
            return false;
        }
        trackerRepository.initialize(orderId, statDate);
        var tracker = trackerRepository.findByOrderId(orderId).orElseThrow();
        if (storeId != null) {
            if (tracker.getStoreId() != 0 && !storeId.equals(tracker.getStoreId())) {
                throw new IllegalArgumentException("Conflicting store for report order");
            }
            tracker.setStoreId(storeId);
        }

        if (authoritative) {
            if (tracker.getPaymentMethod() != null &&
                    (!method.equals(tracker.getPaymentMethod()) || !statDate.equals(tracker.getStatDate()) ||
                            recognizedAmount.compareTo(tracker.getRevenueAmount()) != 0)) {
                throw new IllegalArgumentException("Report correction requires reconciliation");
            }
            tracker.setStatDate(statDate);
            tracker.setPaymentMethod(method);
            tracker.setRevenueAmount(recognizedAmount);
            tracker.setCodAmount(explicitCash != null ? explicitCash
                    : "COD".equals(method) ? recognizedAmount : BigDecimal.ZERO);
            tracker.setTransferAmount(explicitTransfer != null ? explicitTransfer
                    : "BANK_TRANSFER".equals(method) ? recognizedAmount : BigDecimal.ZERO);
            tracker.setExcessAmount(computedExcess);
            tracker.setExpiredReceiptAmount(computedExpired);
        } else if (computedExpired.signum() > 0 && nonNull(tracker.getExpiredReceiptAmount()).signum() == 0) {
            tracker.setExpiredReceiptAmount(computedExpired);
        }

        tracker.setOrderCompleted(tracker.isOrderCompleted() || "order.completed".equals(event.eventType()));
        tracker.setPaymentConfirmed(tracker.isPaymentConfirmed() || "payment.confirmed".equals(event.eventType()));
        if (eventReconciliationStatus != null && !EXPIRED_RECEIPT_STATUS.equals(tracker.getReconciliationStatus())) {
            tracker.setReconciliationStatus(eventReconciliationStatus);
        }
        tracker.setUpdatedAt(Instant.now());

        if (tracker.isContributed()) {
            return handlePostContributionReconciliation(tracker, eventReconciliationStatus);
        }

        if (tracker.getStoreId() == 0) {
            return false;
        }

        if (isExpiredOrder || EXPIRED_RECEIPT_STATUS.equals(tracker.getReconciliationStatus())) {
            tracker.setReconciliationStatus(EXPIRED_RECEIPT_STATUS);
            BigDecimal trackerExpired = nonNull(tracker.getExpiredReceiptAmount());
            if (trackerExpired.signum() > 0) {
                statsRepository.upsertReconciliationDelta(
                        tracker.getStoreId(), tracker.getStatDate(),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, trackerExpired);
            }
            tracker.setContributed(true);
            return false;
        }

        if (tracker.getPaymentMethod() == null) {
            return false;
        }

        BigDecimal reconciledTransfer = BigDecimal.ZERO;
        BigDecimal pendingReconciliation = BigDecimal.ZERO;
        if (tracker.getTransferAmount().signum() > 0) {
            if (explicitReconciledTransfer != null || explicitPendingReconciliation != null) {
                reconciledTransfer = explicitReconciledTransfer != null ? explicitReconciledTransfer : BigDecimal.ZERO;
                pendingReconciliation = explicitPendingReconciliation != null ? explicitPendingReconciliation : BigDecimal.ZERO;
            } else if (isBankReconciled(tracker)) {
                reconciledTransfer = tracker.getTransferAmount();
            } else {
                pendingReconciliation = tracker.getTransferAmount();
            }
        }
        tracker.setReconciledTransferAmount(reconciledTransfer);
        tracker.setPendingReconciliationAmount(pendingReconciliation);

        statsRepository.upsertStatsDelta(tracker.getStoreId(), tracker.getStatDate(), 1,
                tracker.getRevenueAmount(), tracker.getCodAmount(), tracker.getTransferAmount());

        BigDecimal trackerExcess = nonNull(tracker.getExcessAmount());
        BigDecimal trackerExpired = nonNull(tracker.getExpiredReceiptAmount());
        if (reconciledTransfer.signum() != 0 || pendingReconciliation.signum() != 0
                || trackerExcess.signum() != 0 || trackerExpired.signum() != 0) {
            statsRepository.upsertReconciliationDelta(
                    tracker.getStoreId(), tracker.getStatDate(),
                    reconciledTransfer, pendingReconciliation, trackerExcess, trackerExpired);
        }

        if (itemStatsRepository != null && !tracker.isItemsContributed()) {
            projectOrderBreakdownAndItemsFromPayload(tracker, payload);
        }

        tracker.setContributed(true);
        return true;
    }

    public record ItemLineInput(
            String itemType,
            Long itemRefId,
            LocalizedText itemName,
            int quantity,
            BigDecimal lineTotal
    ) {}

    public void projectOrderBreakdownAndItems(
            OrderProjectionTracker tracker,
            BigDecimal discountAmount,
            BigDecimal pointsAmount,
            String orderSource,
            String orderType,
            List<ItemLineInput> rawItems
    ) {
        if (tracker == null || tracker.isItemsContributed()) {
            return;
        }
        BigDecimal safeDiscount = nonNull(discountAmount).max(BigDecimal.ZERO);
        BigDecimal safePoints = nonNull(pointsAmount).max(BigDecimal.ZERO);
        int deliveryDelta = "DELIVERY".equalsIgnoreCase(orderType) ? 1 : 0;
        int pickupDelta = (orderType != null && !"DELIVERY".equalsIgnoreCase(orderType)) ? 1 : 0;
        int webDelta = "WEB".equalsIgnoreCase(orderSource) ? 1 : 0;
        int counterDelta = "COUNTER".equalsIgnoreCase(orderSource) ? 1 : 0;

        if (safeDiscount.signum() != 0 || safePoints.signum() != 0
                || deliveryDelta != 0 || pickupDelta != 0 || webDelta != 0 || counterDelta != 0) {
            statsRepository.upsertBreakdownDelta(
                    tracker.getStoreId(),
                    tracker.getStatDate(),
                    0,
                    BigDecimal.ZERO,
                    safeDiscount,
                    safePoints,
                    deliveryDelta,
                    pickupDelta,
                    webDelta,
                    counterDelta
            );
        }

        if (itemStatsRepository != null && rawItems != null && !rawItems.isEmpty()) {
            Map<String, ItemLineInput> grouped = new LinkedHashMap<>();
            BigDecimal totalGross = BigDecimal.ZERO;
            for (ItemLineInput raw : rawItems) {
                if (raw == null || raw.itemRefId() == null || raw.itemRefId() <= 0 || raw.quantity() <= 0) {
                    continue;
                }
                String normalizedType = "COMBO".equalsIgnoreCase(raw.itemType()) ? "COMBO" : "PRODUCT";
                BigDecimal gross = nonNull(raw.lineTotal()).max(BigDecimal.ZERO);
                totalGross = totalGross.add(gross);
                String key = normalizedType + ":" + raw.itemRefId();
                grouped.merge(key,
                        new ItemLineInput(normalizedType, raw.itemRefId(), raw.itemName(), raw.quantity(), gross),
                        (a, b) -> new ItemLineInput(
                                a.itemType(),
                                a.itemRefId(),
                                b.itemName() != null ? b.itemName() : a.itemName(),
                                a.quantity() + b.quantity(),
                                a.lineTotal().add(b.lineTotal())
                        ));
            }

            List<ItemLineInput> merged = new ArrayList<>(grouped.values());
            BigDecimal totalOrderDiscounts = safeDiscount.add(safePoints);
            BigDecimal netGoodsTarget = totalGross.subtract(totalOrderDiscounts).max(BigDecimal.ZERO);
            BigDecimal allocatedSoFar = BigDecimal.ZERO;

            for (int i = 0; i < merged.size(); i++) {
                ItemLineInput line = merged.get(i);
                BigDecimal allocatedNet;
                if (totalGross.signum() <= 0 || netGoodsTarget.signum() <= 0) {
                    allocatedNet = BigDecimal.ZERO;
                } else if (i == merged.size() - 1) {
                    allocatedNet = netGoodsTarget.subtract(allocatedSoFar).max(BigDecimal.ZERO);
                } else {
                    allocatedNet = netGoodsTarget.multiply(line.lineTotal())
                            .divide(totalGross, 0, RoundingMode.HALF_UP);
                    allocatedSoFar = allocatedSoFar.add(allocatedNet);
                }

                String nameJson = serializeLocalizedName(line.itemName());
                itemStatsRepository.upsertItemDelta(
                        tracker.getStoreId(),
                        tracker.getStatDate(),
                        line.itemType(),
                        line.itemRefId(),
                        nameJson,
                        line.quantity(),
                        1,
                        line.lineTotal(),
                        allocatedNet
                );
            }
        }
        tracker.setItemsContributed(true);
    }

    private void projectOrderBreakdownAndItemsFromPayload(OrderProjectionTracker tracker, JsonNode payload) {
        if (payload == null) return;
        BigDecimal discount = nonNull(resolveOptionalAmountUnchecked(payload, "discountAmount"));
        BigDecimal points = nonNull(resolveOptionalAmountUnchecked(payload, "pointsAmount"));
        String orderSource = payload.path("orderSource").asText(null);
        String orderType = payload.path("type").asText(null);
        List<ItemLineInput> itemInputs = new ArrayList<>();
        JsonNode itemsNode = payload.get("items");
        if (itemsNode != null && itemsNode.isArray()) {
            for (JsonNode node : itemsNode) {
                String itemType = node.path("itemType").asText("PRODUCT");
                Long productId = node.hasNonNull("productId") ? node.get("productId").asLong() : null;
                Long comboId = node.hasNonNull("comboId") ? node.get("comboId").asLong() : null;
                Long refId = "COMBO".equalsIgnoreCase(itemType) ? comboId : productId;
                if (refId == null || refId <= 0) continue;
                LocalizedText i18n = node.hasNonNull("itemNameI18n")
                        ? LocalizedText.fromJsonNode(node.get("itemNameI18n"))
                        : null;
                if (i18n == null && node.hasNonNull("itemName")) {
                    i18n = LocalizedText.ofSingle(node.get("itemName").asText());
                }
                int qty = node.path("quantity").asInt(0);
                BigDecimal lineTotal = node.hasNonNull("lineTotal") && node.get("lineTotal").isNumber()
                        ? node.get("lineTotal").decimalValue()
                        : BigDecimal.ZERO;
                itemInputs.add(new ItemLineInput(itemType, refId, i18n, qty, lineTotal));
            }
        }
        projectOrderBreakdownAndItems(tracker, discount, points, orderSource, orderType, itemInputs);
    }

    private String serializeLocalizedName(LocalizedText name) {
        LocalizedText safe = name != null ? name : LocalizedText.ofSingle("Unknown Item");
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (Exception ex) {
            return "{\"vi\":\"Unknown Item\",\"en\":\"Unknown Item\"}";
        }
    }

    private BigDecimal resolveOptionalAmountUnchecked(JsonNode payload, String field) {
        if (payload != null && payload.hasNonNull(field) && payload.get(field).isNumber()) {
            return payload.get(field).decimalValue();
        }
        return BigDecimal.ZERO;
    }

    private boolean handlePostContributionReconciliation(
            com.clucky.reviewreportservice.entity.OrderProjectionTracker tracker,
            String eventReconciliationStatus
    ) {
        if (nonNull(tracker.getPendingReconciliationAmount()).signum() > 0
                && eventReconciliationStatus != null && RECONCILED_STATUSES.contains(eventReconciliationStatus)) {
            BigDecimal settled = nonNull(tracker.getPendingReconciliationAmount());
            tracker.setReconciledTransferAmount(nonNull(tracker.getReconciledTransferAmount()).add(settled));
            tracker.setPendingReconciliationAmount(BigDecimal.ZERO);
            tracker.setReconciliationStatus("RECONCILED");
            statsRepository.upsertReconciliationDelta(
                    tracker.getStoreId(), tracker.getStatDate(),
                    settled, settled.negate(), BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return false;
    }

    @Transactional
    public void handleOrderCancelled(DomainEvent event) {
        requireEnabled();
        validateEvent(event);
        if (!"order.cancelled".equals(event.eventType())) {
            throw new IllegalArgumentException("Expected cancellation event");
        }
        JsonNode payload = event.payload();
        long orderId = positiveId(payload, "orderId");
        Long storeId = payload.hasNonNull("storeId") ? positiveId(payload, "storeId") : null;
        BigDecimal explicitExpired = resolveOptionalAmount(payload, "expiredReceiptAmount");
        LocalDate statDate = resolveStatDate(event, payload);

        if (processedEventRepository.claim(event.eventId(), event.eventType(), orderId) == 0) {
            return;
        }
        // Only track explicit non-revenue expired-order transfer receipts; never mutate order_count or revenue.
        if (storeId != null && explicitExpired != null && explicitExpired.signum() > 0) {
            statsRepository.upsertReconciliationDelta(
                    storeId, statDate,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, explicitExpired);
        }
        if (itemStatsRepository != null && storeId != null) {
            BigDecimal cancelledAmount = resolveOptionalAmountUnchecked(payload, "totalAmount").max(BigDecimal.ZERO);
            statsRepository.upsertBreakdownDelta(
                    storeId, statDate, 1, cancelledAmount,
                    BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0);
        }
    }

    public LocalDate resolveStatDate(DomainEvent event, JsonNode payload) {
        String field = "order.completed".equals(event.eventType()) ? "completedAt"
                : ("order.cancelled".equals(event.eventType()) ? "cancelledAt" : "paidAt");
        Instant timestamp = event.occurredAt();
        if (payload.hasNonNull(field)) {
            try {
                timestamp = Instant.parse(payload.get(field).asText());
            } catch (DateTimeParseException failure) {
                throw new IllegalArgumentException("Invalid business timestamp");
            }
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("Business timestamp required");
        }
        return timestamp.atZone(VIETNAM_ZONE).toLocalDate();
    }

    private void requireEnabled() {
        if ("false".equals(revenueEvent)) {
            throw new IllegalStateException("Report revenue policy requires approval before enabling projection");
        }
    }

    private void validateEvent(DomainEvent event) {
        if (event == null || event.eventId() == null || event.eventId().isBlank() || event.eventId().length() > 160 ||
                event.eventType() == null || event.occurredAt() == null || event.payload() == null || !event.payload().isObject()) {
            throw new IllegalArgumentException("Invalid report event");
        }
        String source = "payment.confirmed".equals(event.eventType()) ? "payment-service" : "cart-order-service";
        if (!source.equals(event.sourceService())) {
            throw new IllegalArgumentException("Unexpected report event source");
        }
    }

    private long positiveId(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("Positive numeric " + field + " required");
        }
        return value.longValue();
    }

    private BigDecimal resolveAmount(JsonNode payload) {
        String field = "order.completed".equals(revenueEvent) ? "totalAmount" : "amount";
        JsonNode value = payload.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("Numeric revenue amount required");
        }
        return validateVndAmount(value.decimalValue(), "Revenue amount");
    }

    private BigDecimal resolveOptionalAmount(JsonNode payload, String... fieldNames) {
        for (String field : fieldNames) {
            if (payload.has(field) && !payload.get(field).isNull()) {
                JsonNode value = payload.get(field);
                if (!value.isNumber()) {
                    throw new IllegalArgumentException("Numeric " + field + " required");
                }
                return validateVndAmount(value.decimalValue(), field);
            }
        }
        return null;
    }

    private BigDecimal validateVndAmount(BigDecimal amount, String fieldLabel) {
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 0 || amount.compareTo(MAX_VND) > 0) {
            throw new IllegalArgumentException(fieldLabel + " outside supported VND range");
        }
        return amount;
    }

    private String resolvePaymentMethod(JsonNode payload) {
        if ("NO_PAYMENT".equals(payload.path("collectionMethod").asText())
                || "NO_PAYMENT".equals(payload.path("paymentMethod").asText())) {
            if (!payload.path("totalAmount").isNumber()
                    || payload.path("totalAmount").decimalValue().signum() != 0
                    || !"COLLECTED".equals(payload.path("paymentStatus").asText())) {
                throw new IllegalArgumentException("Invalid no-payment order snapshot");
            }
            return "NO_PAYMENT";
        }
        String field = "order.completed".equals(revenueEvent) ? "paymentMethod" : "method";
        String method = payload.path(field).asText(payload.path("paymentMethod").asText());
        // The order retains the customer's original choice (including SPLIT),
        // while collectionMethod records how staff actually settled it.
        String collected = payload.path("collectionMethod").asText();
        if ("order.completed".equals(revenueEvent)
                && Set.of("CASH", "BANK_TRANSFER", "MIXED", "UNVERIFIED").contains(collected)) {
            method = collected;
        }
        if ("SPLIT".equals(method)) method = "MIXED";
        if ("TRANSFER".equals(method)) method = "BANK_TRANSFER";
        if (!Set.of("COD", "BANK_TRANSFER", "CASH", "MIXED", "UNVERIFIED").contains(method)) {
            throw new IllegalArgumentException("Supported payment method required");
        }
        return method;
    }

    private String resolveOptionalReconciliationStatus(DomainEvent event, JsonNode payload, boolean isExpiredOrder) {
        if (isExpiredOrder) {
            return EXPIRED_RECEIPT_STATUS;
        }
        if (("NO_PAYMENT".equals(payload.path("collectionMethod").asText())
                || "NO_PAYMENT".equals(payload.path("paymentMethod").asText()))
                && payload.path("totalAmount").isNumber()
                && payload.path("totalAmount").decimalValue().signum() == 0) {
            return "NOT_REQUIRED";
        }
        if (payload.hasNonNull("reconciliationStatus")) {
            return payload.get("reconciliationStatus").asText().trim().toUpperCase(Locale.ROOT);
        }
        if (payload.path("reconciled").asBoolean(false) || payload.path("bankConfirmed").asBoolean(false)) {
            return "RECONCILED";
        }
        // POS SPLIT is COLLECTED because staff attests the cash leg. Unlike
        // legacy manual collections, a SePay payment.confirmed with a verified
        // bank portion is fully reconciled, not merely staff-declared.
        if ("payment.confirmed".equals(event.eventType())
                && "COUNTER".equals(payload.path("orderSource").asText())
                && payload.path("bankAmount").isNumber()
                && payload.path("bankAmount").decimalValue().signum() > 0) {
            return "RECONCILED";
        }
        if (payload.path("manualAccepted").asBoolean(false) || payload.path("counterDeclared").asBoolean(false)
                || "COLLECTION".equals(payload.path("operationType").asText())
                || "COLLECTED".equals(payload.path("status").asText())
                || "COLLECTED".equals(payload.path("paymentStatus").asText())) {
            return "PENDING_RECONCILIATION";
        }
        if ("payment.confirmed".equals(event.eventType())) {
            return "RECONCILED";
        }
        return null;
    }

    private boolean isBankReconciled(com.clucky.reviewreportservice.entity.OrderProjectionTracker tracker) {
        String status = tracker.getReconciliationStatus();
        if (status != null) {
            if (RECONCILED_STATUSES.contains(status)) {
                return true;
            }
            if (PENDING_RECONCILIATION_STATUSES.contains(status)) {
                return false;
            }
        }
        return tracker.isPaymentConfirmed();
    }

    private BigDecimal nonNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
