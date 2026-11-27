package com.clucky.reviewreportservice.repository;

import com.clucky.reviewreportservice.entity.DailyItemStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface DailyItemStatsRepository extends JpaRepository<DailyItemStats, Long> {

    interface ItemAggregateProjection {
        String getItemType();
        Long getItemRefId();
        String getItemNameJson();
        Long getQuantitySold();
        Long getOrderCount();
        BigDecimal getGrossRevenue();
        BigDecimal getAllocatedNetRevenue();
        Long getStoreCount();
    }

    interface ItemStoreBreakdownProjection {
        Long getStoreId();
        String getItemNameJson();
        Long getQuantitySold();
        Long getOrderCount();
        BigDecimal getGrossRevenue();
        BigDecimal getAllocatedNetRevenue();
    }

    @Modifying
    @Query(value = """
        INSERT INTO review_report_service.daily_item_stats
            (store_id, stat_date, item_type, item_ref_id, item_name_i18n,
             quantity_sold, order_count, gross_revenue, allocated_net_revenue, updated_at)
        VALUES (:storeId, :statDate, :itemType, :itemRefId, CAST(:itemNameJson AS jsonb),
                :quantityDelta, :orderCountDelta, :grossDelta, :allocatedNetDelta, now())
        ON CONFLICT (store_id, stat_date, item_type, item_ref_id)
        DO UPDATE SET
            item_name_i18n = EXCLUDED.item_name_i18n,
            quantity_sold = review_report_service.daily_item_stats.quantity_sold + EXCLUDED.quantity_sold,
            order_count = review_report_service.daily_item_stats.order_count + EXCLUDED.order_count,
            gross_revenue = review_report_service.daily_item_stats.gross_revenue + EXCLUDED.gross_revenue,
            allocated_net_revenue = review_report_service.daily_item_stats.allocated_net_revenue + EXCLUDED.allocated_net_revenue,
            updated_at = now()
        """, nativeQuery = true)
    int upsertItemDelta(
            @Param("storeId") Long storeId,
            @Param("statDate") LocalDate statDate,
            @Param("itemType") String itemType,
            @Param("itemRefId") Long itemRefId,
            @Param("itemNameJson") String itemNameJson,
            @Param("quantityDelta") int quantityDelta,
            @Param("orderCountDelta") int orderCountDelta,
            @Param("grossDelta") BigDecimal grossDelta,
            @Param("allocatedNetDelta") BigDecimal allocatedNetDelta
    );

    @Query(value = """
        SELECT
            d.item_type AS itemType,
            d.item_ref_id AS itemRefId,
            (ARRAY_AGG(d.item_name_i18n::text ORDER BY d.updated_at DESC))[1] AS itemNameJson,
            COALESCE(SUM(d.quantity_sold), 0) AS quantitySold,
            COALESCE(SUM(d.order_count), 0) AS orderCount,
            COALESCE(SUM(d.gross_revenue), 0) AS grossRevenue,
            COALESCE(SUM(d.allocated_net_revenue), 0) AS allocatedNetRevenue,
            COUNT(DISTINCT d.store_id) AS storeCount
        FROM review_report_service.daily_item_stats d
        WHERE d.stat_date BETWEEN :from AND :to
          AND (:storeId IS NULL OR d.store_id = :storeId)
          AND (:itemType IS NULL OR d.item_type = :itemType)
        GROUP BY d.item_type, d.item_ref_id
        """, nativeQuery = true)
    List<ItemAggregateProjection> aggregateItems(
            @Param("storeId") Long storeId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("itemType") String itemType
    );

    @Query(value = """
        SELECT
            d.store_id AS storeId,
            (ARRAY_AGG(d.item_name_i18n::text ORDER BY d.updated_at DESC))[1] AS itemNameJson,
            COALESCE(SUM(d.quantity_sold), 0) AS quantitySold,
            COALESCE(SUM(d.order_count), 0) AS orderCount,
            COALESCE(SUM(d.gross_revenue), 0) AS grossRevenue,
            COALESCE(SUM(d.allocated_net_revenue), 0) AS allocatedNetRevenue
        FROM review_report_service.daily_item_stats d
        WHERE d.stat_date BETWEEN :from AND :to
          AND d.item_type = :itemType
          AND d.item_ref_id = :itemRefId
          AND (:storeId IS NULL OR d.store_id = :storeId)
        GROUP BY d.store_id
        ORDER BY COALESCE(SUM(d.gross_revenue), 0) DESC
        """, nativeQuery = true)
    List<ItemStoreBreakdownProjection> aggregateItemByStore(
            @Param("itemType") String itemType,
            @Param("itemRefId") Long itemRefId,
            @Param("storeId") Long storeId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );
}
