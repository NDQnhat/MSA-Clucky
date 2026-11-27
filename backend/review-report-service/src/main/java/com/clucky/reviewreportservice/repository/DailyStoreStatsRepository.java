package com.clucky.reviewreportservice.repository;

import com.clucky.reviewreportservice.entity.DailyStoreStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyStoreStatsRepository extends JpaRepository<DailyStoreStats, Long> {

    Optional<DailyStoreStats> findByIdAndStoreId(Long id, Long storeId);

    Optional<DailyStoreStats> findByStoreIdAndStatDate(Long storeId, LocalDate statDate);

    List<DailyStoreStats> findByStoreIdAndStatDateBetweenOrderByStatDateAsc(Long storeId, LocalDate from, LocalDate to);

    List<DailyStoreStats> findByStatDateBetweenOrderByStatDateAsc(LocalDate from, LocalDate to);

    @Modifying
    @Query(value = """
        INSERT INTO review_report_service.daily_store_stats
            (store_id, stat_date, order_count, revenue, cod_amount, transfer_amount, updated_at)
        VALUES (:storeId, :statDate, :orderCount, :revenue, :codAmount, :transferAmount, now())
        ON CONFLICT (store_id, stat_date)
        DO UPDATE SET
            order_count = review_report_service.daily_store_stats.order_count + EXCLUDED.order_count,
            revenue = review_report_service.daily_store_stats.revenue + EXCLUDED.revenue,
            cod_amount = review_report_service.daily_store_stats.cod_amount + EXCLUDED.cod_amount,
            transfer_amount = review_report_service.daily_store_stats.transfer_amount + EXCLUDED.transfer_amount,
            updated_at = now()
        """, nativeQuery = true)
    int upsertStatsDelta(
            @Param("storeId") Long storeId,
            @Param("statDate") LocalDate statDate,
            @Param("orderCount") int orderCount,
            @Param("revenue") BigDecimal revenue,
            @Param("codAmount") BigDecimal codAmount,
            @Param("transferAmount") BigDecimal transferAmount
    );

    @Modifying
    @Query(value = """
        INSERT INTO review_report_service.daily_store_stats
            (store_id, stat_date, order_count, revenue, cod_amount, transfer_amount,
             reconciled_transfer_amount, pending_reconciliation_amount, excess_amount, expired_receipt_amount, updated_at)
        VALUES (:storeId, :statDate, 0, 0, 0, 0,
                :reconciledTransferDelta, :pendingReconciliationDelta, :excessDelta, :expiredReceiptDelta, now())
        ON CONFLICT (store_id, stat_date)
        DO UPDATE SET
            reconciled_transfer_amount = GREATEST(0, review_report_service.daily_store_stats.reconciled_transfer_amount + EXCLUDED.reconciled_transfer_amount),
            pending_reconciliation_amount = GREATEST(0, review_report_service.daily_store_stats.pending_reconciliation_amount + EXCLUDED.pending_reconciliation_amount),
            excess_amount = review_report_service.daily_store_stats.excess_amount + EXCLUDED.excess_amount,
            expired_receipt_amount = review_report_service.daily_store_stats.expired_receipt_amount + EXCLUDED.expired_receipt_amount,
            updated_at = now()
        """, nativeQuery = true)
    int upsertReconciliationDelta(
            @Param("storeId") Long storeId,
            @Param("statDate") LocalDate statDate,
            @Param("reconciledTransferDelta") BigDecimal reconciledTransferDelta,
            @Param("pendingReconciliationDelta") BigDecimal pendingReconciliationDelta,
            @Param("excessDelta") BigDecimal excessDelta,
            @Param("expiredReceiptDelta") BigDecimal expiredReceiptDelta
    );

    @Modifying
    @Query(value = """
        INSERT INTO review_report_service.daily_store_stats
            (store_id, stat_date, order_count, revenue, cod_amount, transfer_amount,
             cancelled_order_count, cancelled_amount, total_discount_amount, total_points_amount,
             delivery_order_count, pickup_order_count, web_order_count, counter_order_count, updated_at)
        VALUES (:storeId, :statDate, 0, 0, 0, 0,
                :cancelledCountDelta, :cancelledAmountDelta, :discountDelta, :pointsDelta,
                :deliveryDelta, :pickupDelta, :webDelta, :counterDelta, now())
        ON CONFLICT (store_id, stat_date)
        DO UPDATE SET
            cancelled_order_count = review_report_service.daily_store_stats.cancelled_order_count + EXCLUDED.cancelled_order_count,
            cancelled_amount = review_report_service.daily_store_stats.cancelled_amount + EXCLUDED.cancelled_amount,
            total_discount_amount = review_report_service.daily_store_stats.total_discount_amount + EXCLUDED.total_discount_amount,
            total_points_amount = review_report_service.daily_store_stats.total_points_amount + EXCLUDED.total_points_amount,
            delivery_order_count = review_report_service.daily_store_stats.delivery_order_count + EXCLUDED.delivery_order_count,
            pickup_order_count = review_report_service.daily_store_stats.pickup_order_count + EXCLUDED.pickup_order_count,
            web_order_count = review_report_service.daily_store_stats.web_order_count + EXCLUDED.web_order_count,
            counter_order_count = review_report_service.daily_store_stats.counter_order_count + EXCLUDED.counter_order_count,
            updated_at = now()
        """, nativeQuery = true)
    int upsertBreakdownDelta(
            @Param("storeId") Long storeId,
            @Param("statDate") LocalDate statDate,
            @Param("cancelledCountDelta") int cancelledCountDelta,
            @Param("cancelledAmountDelta") BigDecimal cancelledAmountDelta,
            @Param("discountDelta") BigDecimal discountDelta,
            @Param("pointsDelta") BigDecimal pointsDelta,
            @Param("deliveryDelta") int deliveryDelta,
            @Param("pickupDelta") int pickupDelta,
            @Param("webDelta") int webDelta,
            @Param("counterDelta") int counterDelta
    );
}
