package com.clucky.notificationservice.entity;

import com.clucky.notificationservice.dto.LocalizedText;
import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "notifications", schema = "notification_service")
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "store_id")
    private Long storeId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "title", nullable = false, columnDefinition = "jsonb")
    private LocalizedText title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false, columnDefinition = "jsonb")
    private LocalizedText content;

    @Column(name = "type", nullable = false, length = 30)
    private String type;

    @Column(name = "ref_id", nullable = true)
    private Long refId;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    @Column(name = "operation_key", length = 180)
    private String operationKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long storeId) { this.storeId = storeId; }

    public LocalizedText getTitle() { return title; }
    public void setTitle(LocalizedText title) { this.title = title; }
    public void setTitle(String title) { this.title = LocalizedText.ofSingle(title); }
    public String resolveTitle(String lang) { return title == null ? "" : title.resolve(lang); }

    public LocalizedText getContent() { return content; }
    public void setContent(LocalizedText content) { this.content = content; }
    public void setContent(String content) { this.content = LocalizedText.ofSingle(content); }
    public String resolveContent(String lang) { return content == null ? "" : content.resolve(lang); }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }

    public Boolean getIsRead() { return isRead; }
    public void setIsRead(Boolean isRead) { this.isRead = isRead; }

    public String getOperationKey() { return operationKey; }
    public void setOperationKey(String operationKey) { this.operationKey = operationKey; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
