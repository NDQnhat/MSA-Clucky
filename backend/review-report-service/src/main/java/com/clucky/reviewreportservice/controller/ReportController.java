package com.clucky.reviewreportservice.controller;

import com.clucky.reviewreportservice.config.CurrentActor;
import com.clucky.reviewreportservice.config.LocaleResolverHelper;
import com.clucky.reviewreportservice.dto.DashboardResponse;
import com.clucky.reviewreportservice.dto.ItemRevenueReportResponse;
import com.clucky.reviewreportservice.dto.ItemStoreComparisonResponse;
import com.clucky.reviewreportservice.dto.RevenueReportResponse;
import com.clucky.reviewreportservice.dto.Role;
import com.clucky.reviewreportservice.service.ItemRevenueBackfillService;
import com.clucky.reviewreportservice.service.ReportProjectionService;
import com.clucky.reviewreportservice.service.ReportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reportService;
    private final ItemRevenueBackfillService backfillService;

    public ReportController(ReportService reportService) {
        this(reportService, null);
    }

    @Autowired
    public ReportController(ReportService reportService, ItemRevenueBackfillService backfillService) {
        this.reportService = reportService;
        this.backfillService = backfillService;
    }

    /**
     * BE-42: GET /api/reports/dashboard
     * Quyền: MGR / ADM; scope theo role
     */
    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('STORE_MANAGER', 'ADMIN')")
    public ResponseEntity<DashboardResponse> getDashboard(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        CurrentActor actor = CurrentActor.require();

        DashboardResponse response = reportService.getDashboard(
                actor.userId(), actor.role().name(), actor.storeId(), storeId, from, to);
        return ResponseEntity.ok(response);
    }

    /**
     * BE-42: GET /api/reports/revenue?storeId=&from=&to=
     * Quyền: MGR (đúng quán), ADM (chọn quán hoặc toàn chuỗi) - STAFF bị chặn
     */
    @GetMapping("/revenue")
    @PreAuthorize("hasAnyRole('STORE_MANAGER', 'ADMIN')")
    public ResponseEntity<RevenueReportResponse> getRevenueReport(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        CurrentActor actor = CurrentActor.require();

        RevenueReportResponse response = reportService.getRevenueReport(
                actor.userId(), actor.role().name(), actor.storeId(), storeId, from, to);
        return ResponseEntity.ok(response);
    }

    /**
     * BE-42: GET /api/reports/export?format=xlsx|pdf&storeId=&from=&to=
     * Quyền: MGR (đúng quán), ADM (chọn quán hoặc toàn chuỗi) - STAFF bị chặn
     */
    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('STORE_MANAGER', 'ADMIN')")
    public ResponseEntity<byte[]> exportReport(
            @RequestParam String format,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) throws IOException {
        CurrentActor actor = CurrentActor.require();
        LocalDate effectiveTo = to != null ? to : LocalDate.now(ReportProjectionService.VIETNAM_ZONE);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.withDayOfMonth(1);

        byte[] fileBytes = reportService.exportReport(actor.userId(), actor.role().name(), actor.storeId(),
                storeId, effectiveFrom, effectiveTo, format);

        String trimmedFormat = format != null ? format.trim() : "";
        String normalizedFormat = "xlsx".equalsIgnoreCase(trimmedFormat) ? "xlsx" : "pdf";
        String contentType = normalizedFormat.equals("xlsx")
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : MediaType.APPLICATION_PDF_VALUE;

        Long effectiveStoreId = actor.role() == Role.ADMIN ? storeId : actor.storeId();
        String storePart = (effectiveStoreId != null ? "store" + effectiveStoreId : "all-stores");
        String filename = String.format("revenue-report-%s-%s-%s.%s",
                storePart,
                effectiveFrom,
                effectiveTo,
                normalizedFormat);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .contentLength(fileBytes.length)
                .body(fileBytes);
    }

    @GetMapping("/items")
    @PreAuthorize("hasAnyRole('STORE_MANAGER', 'ADMIN')")
    public ResponseEntity<ItemRevenueReportResponse> getItemRevenueReport(
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "ALL") String itemType,
            @RequestParam(defaultValue = "revenue") String sortBy,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage
    ) {
        CurrentActor actor = CurrentActor.require();
        return ResponseEntity.ok(reportService.getItemRevenueReport(
                actor.userId(),
                actor.role().name(),
                actor.storeId(),
                storeId,
                from,
                to,
                itemType,
                sortBy,
                limit,
                LocaleResolverHelper.resolve(lang, acceptLanguage)
        ));
    }

    @GetMapping("/items/{itemType}/{itemRefId}/stores")
    @PreAuthorize("hasAnyRole('STORE_MANAGER', 'ADMIN')")
    public ResponseEntity<ItemStoreComparisonResponse> getItemStoreComparison(
            @PathVariable String itemType,
            @PathVariable Long itemRefId,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage
    ) {
        CurrentActor actor = CurrentActor.require();
        return ResponseEntity.ok(reportService.getItemStoreComparison(
                actor.userId(),
                actor.role().name(),
                actor.storeId(),
                storeId,
                itemType,
                itemRefId,
                from,
                to,
                LocaleResolverHelper.resolve(lang, acceptLanguage)
        ));
    }

    @GetMapping("/items/export")
    @PreAuthorize("hasAnyRole('STORE_MANAGER', 'ADMIN')")
    public ResponseEntity<byte[]> exportItemReport(
            @RequestParam String format,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "ALL") String itemType,
            @RequestParam(name = "lang", required = false) String lang,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage
    ) throws IOException {
        CurrentActor actor = CurrentActor.require();
        LocalDate effectiveTo = to != null ? to : LocalDate.now(ReportProjectionService.VIETNAM_ZONE);
        LocalDate effectiveFrom = from != null ? from : effectiveTo.withDayOfMonth(1);
        byte[] fileBytes = reportService.exportItemReport(
                actor.userId(),
                actor.role().name(),
                actor.storeId(),
                storeId,
                effectiveFrom,
                effectiveTo,
                itemType,
                format,
                LocaleResolverHelper.resolve(lang, acceptLanguage)
        );
        String trimmedFormat = format != null ? format.trim() : "";
        String normalizedFormat = "xlsx".equalsIgnoreCase(trimmedFormat) ? "xlsx" : "pdf";
        String contentType = normalizedFormat.equals("xlsx")
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : MediaType.APPLICATION_PDF_VALUE;
        Long effectiveStoreId = actor.role() == Role.ADMIN ? storeId : actor.storeId();
        String storePart = (effectiveStoreId != null ? "store" + effectiveStoreId : "all-stores");
        String filename = String.format("item-revenue-report-%s-%s-%s.%s",
                storePart, effectiveFrom, effectiveTo, normalizedFormat);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .contentLength(fileBytes.length)
                .body(fileBytes);
    }

    @PostMapping("/items/backfill")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> backfillItemRevenue() {
        int processed = backfillService != null ? backfillService.runBackfill() : 0;
        return ResponseEntity.ok(Map.of("processedOrders", processed));
    }
}
