package com.inninglog.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(
        name = "notification_outbox",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notification_outbox_user_idempotency",
                columnNames = {"user_id", "idempotency_key"})
)
public class NotificationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String idempotencyKey;

    @Column(nullable = false, unique = true)
    private Long notificationId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType notificationType;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 500)
    private String body;

    @Column(nullable = false, columnDefinition = "text")
    private String dataJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationOutboxStatus status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant completedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected NotificationOutbox() {
    }

    public NotificationOutbox(
            String idempotencyKey,
            Long notificationId,
            Long userId,
            NotificationType notificationType,
            String title,
            String body,
            String dataJson,
            Instant createdAt
    ) {
        this.idempotencyKey = idempotencyKey;
        this.notificationId = notificationId;
        this.userId = userId;
        this.notificationType = notificationType;
        this.title = title;
        this.body = body;
        this.dataJson = dataJson;
        this.status = NotificationOutboxStatus.PENDING;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void markProcessing(Instant now) {
        this.status = NotificationOutboxStatus.PROCESSING;
        this.updatedAt = now;
    }

    public void markCompleted(boolean hasPermanentFailures, Instant now) {
        this.status = hasPermanentFailures
                ? NotificationOutboxStatus.COMPLETED_WITH_FAILURES
                : NotificationOutboxStatus.COMPLETED;
        this.updatedAt = now;
        this.completedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getNotificationId() {
        return notificationId;
    }

    public NotificationType getNotificationType() {
        return notificationType;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getDataJson() {
        return dataJson;
    }

    public NotificationOutboxStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
