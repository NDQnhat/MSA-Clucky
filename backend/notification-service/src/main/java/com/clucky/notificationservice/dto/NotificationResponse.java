package com.clucky.notificationservice.dto;

import java.time.Instant;

public record NotificationResponse(
        Long id,
        Long userId,
        String title,
        LocalizedText titleI18n,
        String content,
        LocalizedText contentI18n,
        String type,
        Long refId,
        Boolean isRead,
        Instant createdAt
) {
    public NotificationResponse(
            Long id,
            Long userId,
            String title,
            String content,
            String type,
            Long refId,
            Boolean isRead,
            Instant createdAt
    ) {
        this(id, userId, title, LocalizedText.ofSingle(title), content, LocalizedText.ofSingle(content),
                type, refId, isRead, createdAt);
    }
}
