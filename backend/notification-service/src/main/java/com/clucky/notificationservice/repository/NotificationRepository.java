package com.clucky.notificationservice.repository;

import com.clucky.notificationservice.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    @Query("SELECT n FROM Notification n WHERE n.id = :id AND n.userId = :userId AND (:storeId IS NULL OR n.storeId = :storeId)")
    Optional<Notification> findOwned(@Param("id") Long id, @Param("userId") Long userId, @Param("storeId") Long storeId);

    Optional<Notification> findByOperationKey(String operationKey);

    Page<Notification> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT n FROM Notification n WHERE n.userId = :userId AND (:storeId IS NULL OR n.storeId = :storeId) "
            + "AND (:unread = false OR n.isRead = false) AND (:type IS NULL OR n.type = :type)")
    Page<Notification> findInbox(@Param("userId") Long userId, @Param("storeId") Long storeId,
            @Param("unread") boolean unread, @Param("type") String type, Pageable pageable);

    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.userId = :userId "
            + "AND (:storeId IS NULL OR n.storeId = :storeId) AND n.isRead = false")
    int markAllAsReadByUserId(@Param("userId") Long userId, @Param("storeId") Long storeId);

    @Query("SELECT count(n) FROM Notification n WHERE n.userId = :userId AND n.isRead = false "
            + "AND (:storeId IS NULL OR n.storeId = :storeId)")
    long countUnread(@Param("userId") Long userId, @Param("storeId") Long storeId);
}
