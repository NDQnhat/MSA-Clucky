package com.clucky.reviewreportservice.service;

import com.clucky.reviewreportservice.dto.DailyStoreStatsResponse;
import com.clucky.reviewreportservice.dto.DashboardResponse;
import com.clucky.reviewreportservice.dto.ItemRevenueReportResponse;
import com.clucky.reviewreportservice.dto.ItemStoreComparisonResponse;
import com.clucky.reviewreportservice.dto.LocalizedText;
import com.clucky.reviewreportservice.dto.PublicBestSellerItemResponse;
import com.clucky.reviewreportservice.dto.PublicItemHighlightsResponse;
import com.clucky.reviewreportservice.dto.RevenueReportResponse;
import com.clucky.reviewreportservice.entity.DailyStoreStats;
import com.clucky.reviewreportservice.exception.ForbiddenException;
import com.clucky.reviewreportservice.mapper.DomainMapper;
import com.clucky.reviewreportservice.repository.DailyItemStatsRepository;
import com.clucky.reviewreportservice.repository.DailyStoreStatsRepository;
import com.clucky.reviewreportservice.repository.ReviewRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    public static final long MAX_RANGE_DAYS_INCLUSIVE = 366L;

    private final DailyStoreStatsRepository statsRepository;
    private final DailyItemStatsRepository itemStatsRepository;
    private final ReviewRepository reviewRepository;
    private final ReportExportService exportService;
    private final ObjectMapper objectMapper;
    private final String revenueEventPolicy;

    public ReportService(DailyStoreStatsRepository statsRepository, ReportExportService exportService) {
        this(statsRepository, exportService, "false");
    }

    public ReportService(
            DailyStoreStatsRepository statsRepository,
            ReportExportService exportService,
            String revenueEventPolicy
    ) {
        this(statsRepository, null, null, exportService, new ObjectMapper(), revenueEventPolicy);
    }

    @Autowired
    public ReportService(
            DailyStoreStatsRepository statsRepository,
            DailyItemStatsRepository itemStatsRepository,
            ReviewRepository reviewRepository,
            ReportExportService exportService,
            ObjectMapper objectMapper,
            @Value("${clucky.reports.revenue-event:false}") String revenueEventPolicy
    ) {
        this.statsRepository = statsRepository;
        this.itemStatsRepository = itemStatsRepository;
        this.reviewRepository = reviewRepository;
        this.exportService = exportService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.revenueEventPolicy = (revenueEventPolicy == null || revenueEventPolicy.isBlank()) ? "false" : revenueEventPolicy.trim();
    }

    /**
     * BE-42: Lấy dữ liệu Dashboard theo role & scope
     */
    @Transactional(readOnly = true)
    public DashboardResponse getDashboard(Long actorUserId, String actorRole, Long actorStoreId,
                                          Long requestedStoreId, LocalDate from, LocalDate to) {
        Long effectiveStoreId = resolveEffectiveStoreId(actorRole, actorStoreId, requestedStoreId, false);
        LocalDate today = LocalDate.now(ReportProjectionService.VIETNAM_ZONE);

        RevenueReportResponse period = getRevenueReport(
                actorUserId, actorRole, actorStoreId, requestedStoreId, from, to);

        BigDecimal todayRev = BigDecimal.ZERO;
        int todayOrders = 0;
        BigDecimal todayCod = BigDecimal.ZERO;
        BigDecimal todayTransfer = BigDecimal.ZERO;
        BigDecimal todayReconciledTransfer = BigDecimal.ZERO;
        BigDecimal todayPendingReconciliation = BigDecimal.ZERO;
        BigDecimal todayExcess = BigDecimal.ZERO;
        BigDecimal todayExpiredReceipt = BigDecimal.ZERO;
        Instant todayUpdatedAt = null;

        if (effectiveStoreId != null) {
            var todayOpt = statsRepository.findByStoreIdAndStatDate(effectiveStoreId, today);
            if (todayOpt.isPresent()) {
                DailyStoreStats s = todayOpt.get();
                todayRev = amountOrZero(s.getRevenue());
                todayOrders = countOrZero(s.getOrderCount());
                todayCod = amountOrZero(s.getCodAmount());
                todayTransfer = amountOrZero(s.getTransferAmount());
                todayReconciledTransfer = amountOrZero(s.getReconciledTransferAmount());
                todayPendingReconciliation = amountOrZero(s.getPendingReconciliationAmount());
                todayExcess = amountOrZero(s.getExcessAmount());
                todayExpiredReceipt = amountOrZero(s.getExpiredReceiptAmount());
                todayUpdatedAt = s.getUpdatedAt();
            }
        } else {
            List<DailyStoreStats> todayAll = statsRepository.findByStatDateBetweenOrderByStatDateAsc(today, today);
            for (DailyStoreStats s : todayAll) {
                todayRev = todayRev.add(amountOrZero(s.getRevenue()));
                todayOrders += countOrZero(s.getOrderCount());
                todayCod = todayCod.add(amountOrZero(s.getCodAmount()));
                todayTransfer = todayTransfer.add(amountOrZero(s.getTransferAmount()));
                todayReconciledTransfer = todayReconciledTransfer.add(amountOrZero(s.getReconciledTransferAmount()));
                todayPendingReconciliation = todayPendingReconciliation.add(amountOrZero(s.getPendingReconciliationAmount()));
                todayExcess = todayExcess.add(amountOrZero(s.getExcessAmount()));
                todayExpiredReceipt = todayExpiredReceipt.add(amountOrZero(s.getExpiredReceiptAmount()));
                todayUpdatedAt = latestInstant(todayUpdatedAt, s.getUpdatedAt());
            }
        }

        Instant lastUpdatedAt = latestInstant(period.lastUpdatedAt(), todayUpdatedAt);
        String scope = effectiveStoreId == null ? "CHAIN" : "STORE";
        log.info("Dashboard fetched by {} #{} (scope: {}, store: {})", actorRole, actorUserId, scope, effectiveStoreId);

        List<DailyStoreStats> periodRows = effectiveStoreId != null
                ? statsRepository.findByStoreIdAndStatDateBetweenOrderByStatDateAsc(effectiveStoreId, period.from(), period.to())
                : statsRepository.findByStatDateBetweenOrderByStatDateAsc(period.from(), period.to());

        BigDecimal periodDiscountAmount = BigDecimal.ZERO;
        BigDecimal periodPointsAmount = BigDecimal.ZERO;
        int periodCancelledOrderCount = 0;
        BigDecimal periodCancelledAmount = BigDecimal.ZERO;
        int periodDeliveryOrderCount = 0;
        int periodPickupOrderCount = 0;
        int periodWebOrderCount = 0;
        int periodCounterOrderCount = 0;
        for (DailyStoreStats s : periodRows) {
            periodDiscountAmount = periodDiscountAmount.add(amountOrZero(s.getTotalDiscountAmount()));
            periodPointsAmount = periodPointsAmount.add(amountOrZero(s.getTotalPointsAmount()));
            periodCancelledOrderCount += countOrZero(s.getCancelledOrderCount());
            periodCancelledAmount = periodCancelledAmount.add(amountOrZero(s.getCancelledAmount()));
            periodDeliveryOrderCount += countOrZero(s.getDeliveryOrderCount());
            periodPickupOrderCount += countOrZero(s.getPickupOrderCount());
            periodWebOrderCount += countOrZero(s.getWebOrderCount());
            periodCounterOrderCount += countOrZero(s.getCounterOrderCount());
        }

        long periodDays = ChronoUnit.DAYS.between(period.from(), period.to()) + 1;
        LocalDate prevTo = period.from().minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(periodDays - 1);
        List<DailyStoreStats> prevRows = effectiveStoreId != null
                ? statsRepository.findByStoreIdAndStatDateBetweenOrderByStatDateAsc(effectiveStoreId, prevFrom, prevTo)
                : statsRepository.findByStatDateBetweenOrderByStatDateAsc(prevFrom, prevTo);
        BigDecimal prevRevenue = BigDecimal.ZERO;
        int prevOrders = 0;
        for (DailyStoreStats s : prevRows) {
            prevRevenue = prevRevenue.add(amountOrZero(s.getRevenue()));
            prevOrders += countOrZero(s.getOrderCount());
        }

        return new DashboardResponse(
                scope,
                effectiveStoreId,
                period.from(),
                period.to(),
                todayRev,
                todayOrders,
                todayCod,
                todayTransfer,
                period.totalRevenue(),
                period.totalOrderCount(),
                todayReconciledTransfer,
                todayPendingReconciliation,
                todayExcess,
                todayExpiredReceipt,
                period.totalCodAmount(),
                period.totalTransferAmount(),
                period.totalReconciledTransferAmount(),
                period.totalPendingReconciliationAmount(),
                period.totalExcessAmount(),
                period.totalExpiredReceiptAmount(),
                period.projectionActive(),
                period.revenueEventPolicy(),
                lastUpdatedAt,
                OffsetDateTime.now(ReportProjectionService.VIETNAM_ZONE),
                prevRevenue,
                prevOrders,
                periodDiscountAmount,
                periodPointsAmount,
                periodCancelledOrderCount,
                periodCancelledAmount,
                periodDeliveryOrderCount,
                periodPickupOrderCount,
                periodWebOrderCount,
                periodCounterOrderCount
        );
    }

    /**
     * BE-42: Lấy dữ liệu Báo cáo Doanh thu theo ngày
     */
    @Transactional(readOnly = true)
    public RevenueReportResponse getRevenueReport(
            Long actorUserId,
            String actorRole,
            Long actorStoreId,
            Long requestedStoreId,
            LocalDate from,
            LocalDate to
    ) {
        // RBAC scope enforced first so unauthorized actors cannot probe date/filter validation
        Long effectiveStoreId = resolveEffectiveStoreId(actorRole, actorStoreId, requestedStoreId, false);

        LocalDate effectiveTo = to != null ? to : LocalDate.now(ReportProjectionService.VIETNAM_ZONE);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.withDayOfMonth(1);

        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("Ngày bắt đầu (from) không được lớn hơn ngày kết thúc (to)!");
        }
        long rangeDays = ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) + 1;
        if (rangeDays > MAX_RANGE_DAYS_INCLUSIVE) {
            throw new IllegalArgumentException("Khoảng thời gian báo cáo tối đa " + MAX_RANGE_DAYS_INCLUSIVE + " ngày!");
        }

        List<DailyStoreStats> rawList;
        if (effectiveStoreId != null) {
            rawList = statsRepository.findByStoreIdAndStatDateBetweenOrderByStatDateAsc(effectiveStoreId, effectiveFrom, effectiveTo);
        } else {
            rawList = statsRepository.findByStatDateBetweenOrderByStatDateAsc(effectiveFrom, effectiveTo);
        }

        BigDecimal totalRevenue = BigDecimal.ZERO;
        int totalOrders = 0;
        BigDecimal totalCod = BigDecimal.ZERO;
        BigDecimal totalTransfer = BigDecimal.ZERO;
        BigDecimal totalReconciledTransfer = BigDecimal.ZERO;
        BigDecimal totalPendingReconciliation = BigDecimal.ZERO;
        BigDecimal totalExcess = BigDecimal.ZERO;
        BigDecimal totalExpiredReceipt = BigDecimal.ZERO;
        Instant lastUpdatedAt = null;

        for (DailyStoreStats s : rawList) {
            totalRevenue = totalRevenue.add(amountOrZero(s.getRevenue()));
            totalOrders += countOrZero(s.getOrderCount());
            totalCod = totalCod.add(amountOrZero(s.getCodAmount()));
            totalTransfer = totalTransfer.add(amountOrZero(s.getTransferAmount()));
            totalReconciledTransfer = totalReconciledTransfer.add(amountOrZero(s.getReconciledTransferAmount()));
            totalPendingReconciliation = totalPendingReconciliation.add(amountOrZero(s.getPendingReconciliationAmount()));
            totalExcess = totalExcess.add(amountOrZero(s.getExcessAmount()));
            totalExpiredReceipt = totalExpiredReceipt.add(amountOrZero(s.getExpiredReceiptAmount()));
            lastUpdatedAt = latestInstant(lastUpdatedAt, s.getUpdatedAt());
        }

        List<DailyStoreStatsResponse> items = rawList.stream()
                .map(DomainMapper::toResponse)
                .toList();

        boolean projectionActive = java.util.Set.of("order.completed", "payment.confirmed").contains(revenueEventPolicy);

        return new RevenueReportResponse(
                effectiveStoreId,
                effectiveFrom,
                effectiveTo,
                totalRevenue,
                totalOrders,
                totalCod,
                totalTransfer,
                totalReconciledTransfer,
                totalPendingReconciliation,
                totalExcess,
                totalExpiredReceipt,
                projectionActive,
                revenueEventPolicy,
                lastUpdatedAt,
                items
        );
    }

    /**
     * BE-42: Xuất file báo cáo doanh thu (XLSX hoặc PDF)
     */
    @Transactional(readOnly = true)
    public byte[] exportReport(
            Long actorUserId,
            String actorRole,
            Long actorStoreId,
            Long requestedStoreId,
            LocalDate from,
            LocalDate to,
            String format
    ) throws IOException {
        // RBAC scope must fail before format or filter validation
        resolveEffectiveStoreId(actorRole, actorStoreId, requestedStoreId, false);

        String normalizedFormat = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (!"xlsx".equals(normalizedFormat) && !"pdf".equals(normalizedFormat)) {
            throw new IllegalArgumentException("Định dạng xuất file không hợp lệ! Chỉ hỗ trợ 'xlsx' hoặc 'pdf'");
        }

        RevenueReportResponse report = getRevenueReport(actorUserId, actorRole, actorStoreId, requestedStoreId, from, to);

        if ("xlsx".equals(normalizedFormat)) {
            return exportService.exportToExcel(report);
        }
        return exportService.exportToPdf(report);
    }

    @Transactional(readOnly = true)
    public ItemRevenueReportResponse getItemRevenueReport(
            Long actorUserId,
            String actorRole,
            Long actorStoreId,
            Long requestedStoreId,
            LocalDate from,
            LocalDate to,
            String itemType,
            String sortBy,
            int limit,
            String lang
    ) {
        Long effectiveStoreId = resolveEffectiveStoreId(actorRole, actorStoreId, requestedStoreId, false);
        LocalDate effectiveTo = to != null ? to : LocalDate.now(ReportProjectionService.VIETNAM_ZONE);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.withDayOfMonth(1);
        validateRange(effectiveFrom, effectiveTo);

        String normalizedItemType = normalizeItemTypeFilter(itemType);
        String normalizedSort = "quantity".equalsIgnoreCase(sortBy) ? "quantity" : "revenue";
        int clampedLimit = Math.min(Math.max(1, limit), 500);

        List<DailyItemStatsRepository.ItemAggregateProjection> rows = itemStatsRepository == null
                ? List.of()
                : itemStatsRepository.aggregateItems(effectiveStoreId, effectiveFrom, effectiveTo, normalizedItemType);

        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalAllocatedNet = BigDecimal.ZERO;
        long totalQty = 0L;
        for (var r : rows) {
            totalGross = totalGross.add(amountOrZero(r.getGrossRevenue()));
            totalAllocatedNet = totalAllocatedNet.add(amountOrZero(r.getAllocatedNetRevenue()));
            totalQty += r.getQuantitySold() != null ? r.getQuantitySold() : 0L;
        }

        final BigDecimal denominator = totalGross;
        Comparator<DailyItemStatsRepository.ItemAggregateProjection> comparator = "quantity".equals(normalizedSort)
                ? Comparator.comparingLong((DailyItemStatsRepository.ItemAggregateProjection r) -> r.getQuantitySold() != null ? r.getQuantitySold() : 0L).reversed()
                        .thenComparing(r -> amountOrZero(r.getGrossRevenue()), Comparator.reverseOrder())
                : Comparator.comparing((DailyItemStatsRepository.ItemAggregateProjection r) -> amountOrZero(r.getGrossRevenue())).reversed()
                        .thenComparingLong(r -> -(r.getQuantitySold() != null ? r.getQuantitySold() : 0L));

        List<ItemRevenueReportResponse.ItemRevenueEntry> entries = rows.stream()
                .sorted(comparator)
                .limit(clampedLimit)
                .map(r -> {
                    LocalizedText i18n = parseItemName(r.getItemNameJson());
                    BigDecimal gross = amountOrZero(r.getGrossRevenue());
                    double sharePct = denominator.signum() > 0
                            ? gross.multiply(BigDecimal.valueOf(100))
                                    .divide(denominator, 1, RoundingMode.HALF_UP)
                                    .doubleValue()
                            : 0.0;
                    return new ItemRevenueReportResponse.ItemRevenueEntry(
                            r.getItemType(),
                            r.getItemRefId(),
                            i18n.resolve(lang),
                            i18n,
                            r.getQuantitySold() != null ? r.getQuantitySold() : 0L,
                            r.getOrderCount() != null ? r.getOrderCount() : 0L,
                            gross,
                            amountOrZero(r.getAllocatedNetRevenue()),
                            sharePct,
                            r.getStoreCount() != null ? r.getStoreCount() : 0L
                    );
                })
                .toList();

        return new ItemRevenueReportResponse(
                effectiveStoreId,
                effectiveFrom,
                effectiveTo,
                normalizedItemType != null ? normalizedItemType : "ALL",
                normalizedSort,
                totalGross,
                totalAllocatedNet,
                totalQty,
                rows.size(),
                entries
        );
    }

    @Transactional(readOnly = true)
    public ItemStoreComparisonResponse getItemStoreComparison(
            Long actorUserId,
            String actorRole,
            Long actorStoreId,
            Long requestedStoreId,
            String itemType,
            Long itemRefId,
            LocalDate from,
            LocalDate to,
            String lang
    ) {
        Long effectiveStoreId = resolveEffectiveStoreId(actorRole, actorStoreId, requestedStoreId, false);
        if (itemRefId == null || itemRefId <= 0) {
            throw new IllegalArgumentException("itemRefId phải là số dương!");
        }
        String normalizedType = "COMBO".equalsIgnoreCase(itemType) ? "COMBO"
                : ("PRODUCT".equalsIgnoreCase(itemType) ? "PRODUCT" : null);
        if (normalizedType == null) {
            throw new IllegalArgumentException("itemType phải là PRODUCT hoặc COMBO!");
        }
        LocalDate effectiveTo = to != null ? to : LocalDate.now(ReportProjectionService.VIETNAM_ZONE);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.withDayOfMonth(1);
        validateRange(effectiveFrom, effectiveTo);

        List<DailyItemStatsRepository.ItemStoreBreakdownProjection> rows = itemStatsRepository == null
                ? List.of()
                : itemStatsRepository.aggregateItemByStore(normalizedType, itemRefId, effectiveStoreId, effectiveFrom, effectiveTo);

        long totalQty = 0L;
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;
        LocalizedText itemNameI18n = LocalizedText.ofSingle("#" + itemRefId);
        for (var r : rows) {
            if (r.getItemNameJson() != null) {
                itemNameI18n = parseItemName(r.getItemNameJson());
            }
            totalQty += r.getQuantitySold() != null ? r.getQuantitySold() : 0L;
            totalGross = totalGross.add(amountOrZero(r.getGrossRevenue()));
            totalNet = totalNet.add(amountOrZero(r.getAllocatedNetRevenue()));
        }

        final BigDecimal denominator = totalGross;
        List<ItemStoreComparisonResponse.StoreItemMetric> storeMetrics = rows.stream()
                .map(r -> {
                    BigDecimal gross = amountOrZero(r.getGrossRevenue());
                    double sharePct = denominator.signum() > 0
                            ? gross.multiply(BigDecimal.valueOf(100))
                                    .divide(denominator, 1, RoundingMode.HALF_UP)
                                    .doubleValue()
                            : 0.0;
                    return new ItemStoreComparisonResponse.StoreItemMetric(
                            r.getStoreId(),
                            r.getQuantitySold() != null ? r.getQuantitySold() : 0L,
                            r.getOrderCount() != null ? r.getOrderCount() : 0L,
                            gross,
                            amountOrZero(r.getAllocatedNetRevenue()),
                            sharePct
                    );
                })
                .toList();

        return new ItemStoreComparisonResponse(
                normalizedType,
                itemRefId,
                itemNameI18n.resolve(lang),
                itemNameI18n,
                effectiveFrom,
                effectiveTo,
                totalQty,
                totalGross,
                totalNet,
                storeMetrics
        );
    }

    @Transactional(readOnly = true)
    public byte[] exportItemReport(
            Long actorUserId,
            String actorRole,
            Long actorStoreId,
            Long requestedStoreId,
            LocalDate from,
            LocalDate to,
            String itemType,
            String format,
            String lang
    ) throws IOException {
        resolveEffectiveStoreId(actorRole, actorStoreId, requestedStoreId, false);
        String normalizedFormat = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (!"xlsx".equals(normalizedFormat) && !"pdf".equals(normalizedFormat)) {
            throw new IllegalArgumentException("Định dạng xuất file không hợp lệ! Chỉ hỗ trợ 'xlsx' hoặc 'pdf'");
        }
        ItemRevenueReportResponse report = getItemRevenueReport(
                actorUserId, actorRole, actorStoreId, requestedStoreId, from, to, itemType, "revenue", 500, lang);
        if ("xlsx".equals(normalizedFormat)) {
            return exportService.exportItemsToExcel(report);
        }
        return exportService.exportItemsToPdf(report);
    }

    @Transactional(readOnly = true)
    public PublicItemHighlightsResponse getPublicHighlights() {
        LocalDate from = LocalDate.now(ReportProjectionService.VIETNAM_ZONE).minusDays(29);
        List<Long> topProducts = new java.util.ArrayList<>();
        List<Long> topCombos = new java.util.ArrayList<>();
        if (itemStatsRepository != null) {
            for (var row : itemStatsRepository.findTopPublicSellingItems(from)) {
                if (row.getItemRefId() == null) continue;
                if ("COMBO".equalsIgnoreCase(row.getItemType())) {
                    if (topCombos.size() < 8) topCombos.add(row.getItemRefId());
                } else {
                    if (topProducts.size() < 12) topProducts.add(row.getItemRefId());
                }
            }
        }
        java.util.Map<Long, PublicItemHighlightsResponse.ProductRatingSummary> ratings = new java.util.LinkedHashMap<>();
        if (reviewRepository != null) {
            for (var r : reviewRepository.aggregateVisibleRatingsByProduct()) {
                if (r.getProductId() == null) continue;
                double avg = r.getAverageRating() != null ? Math.round(r.getAverageRating() * 10.0) / 10.0 : 0.0;
                long count = r.getReviewCount() != null ? r.getReviewCount() : 0L;
                ratings.put(r.getProductId(), new PublicItemHighlightsResponse.ProductRatingSummary(avg, count));
            }
        }
        return new PublicItemHighlightsResponse(topProducts, topCombos, ratings);
    }

    @Transactional(readOnly = true)
    public List<PublicBestSellerItemResponse> getPublicBestSellers(
            Long storeId,
            int days,
            int limit,
            String itemType,
            String lang
    ) {
        if (storeId != null && storeId <= 0) {
            throw new IllegalArgumentException("storeId phải là số dương!");
        }
        int clampedDays = Math.min(Math.max(1, days), 90);
        int clampedLimit = Math.min(Math.max(1, limit), 20);
        String normalizedType = normalizeItemTypeFilter(itemType);
        LocalDate from = LocalDate.now(ReportProjectionService.VIETNAM_ZONE).minusDays(clampedDays - 1L);
        if (itemStatsRepository == null) {
            return List.of();
        }
        return itemStatsRepository.findPublicBestSellers(storeId, from, normalizedType, clampedLimit)
                .stream()
                .filter(row -> row.getItemRefId() != null)
                .map(row -> {
                    LocalizedText i18n = parseItemName(row.getItemNameJson());
                    long qty = row.getQuantitySold() != null ? row.getQuantitySold() : 0L;
                    return new PublicBestSellerItemResponse(
                            row.getItemType(),
                            row.getItemRefId(),
                            i18n.resolve(lang),
                            i18n,
                            qty
                    );
                })
                .toList();
    }

    private void validateRange(LocalDate effectiveFrom, LocalDate effectiveTo) {
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("Ngày bắt đầu (from) không được lớn hơn ngày kết thúc (to)!");
        }
        long rangeDays = ChronoUnit.DAYS.between(effectiveFrom, effectiveTo) + 1;
        if (rangeDays > MAX_RANGE_DAYS_INCLUSIVE) {
            throw new IllegalArgumentException("Khoảng thời gian báo cáo tối đa " + MAX_RANGE_DAYS_INCLUSIVE + " ngày!");
        }
    }

    private String normalizeItemTypeFilter(String itemType) {
        if (itemType == null || itemType.isBlank() || "ALL".equalsIgnoreCase(itemType.trim())) {
            return null;
        }
        String upper = itemType.trim().toUpperCase(Locale.ROOT);
        if ("PRODUCT".equals(upper) || "COMBO".equals(upper)) {
            return upper;
        }
        throw new IllegalArgumentException("Loại món không hợp lệ (chỉ hỗ trợ ALL, PRODUCT, COMBO)!");
    }

    private LocalizedText parseItemName(String json) {
        if (json == null || json.isBlank()) {
            return LocalizedText.ofSingle("");
        }
        try {
            LocalizedText parsed = objectMapper.readValue(json, LocalizedText.class);
            return parsed != null ? parsed : LocalizedText.ofSingle("");
        } catch (Exception ex) {
            return LocalizedText.ofSingle(json);
        }
    }

    /**
     * Xác định cửa hàng có hiệu lực và kiểm tra quyền RBAC theo quán
     */
    private Long resolveEffectiveStoreId(String role, Long actorStoreId, Long requestedStoreId, boolean allowStaff) {
        if ("STAFF".equalsIgnoreCase(role) && !allowStaff) {
            throw new ForbiddenException("Nhân viên (Staff) không có quyền truy cập báo cáo doanh thu của cửa hàng!");
        }
        if ("ADMIN".equalsIgnoreCase(role)) {
            if (requestedStoreId != null && requestedStoreId <= 0) {
                throw new IllegalArgumentException("storeId phải là số dương!");
            }
            return requestedStoreId;
        }
        if ("STORE_MANAGER".equalsIgnoreCase(role) || (allowStaff && "STAFF".equalsIgnoreCase(role))) {
            if (actorStoreId == null || actorStoreId <= 0) {
                throw new ForbiddenException("Không xác định được cửa hàng của nhân sự!");
            }
            if (requestedStoreId != null && requestedStoreId <= 0) {
                throw new IllegalArgumentException("storeId phải là số dương!");
            }
            if (requestedStoreId != null && !requestedStoreId.equals(actorStoreId)) {
                throw new ForbiddenException("Bạn không có quyền xem báo cáo của cửa hàng khác!");
            }
            return actorStoreId;
        }
        throw new ForbiddenException("Vai trò không hợp lệ để xem báo cáo!");
    }

    private BigDecimal amountOrZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private int countOrZero(Integer value) {
        return value != null ? value : 0;
    }

    private Instant latestInstant(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return second.isAfter(first) ? second : first;
    }
}
