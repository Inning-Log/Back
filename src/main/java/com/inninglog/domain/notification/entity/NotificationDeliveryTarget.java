package com.inninglog.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(
        name = "notification_delivery_targets",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notification_target_outbox_registration",
                columnNames = {"outbox_id", "push_registration_id"})
)
public class NotificationDeliveryTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "outbox_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_notification_target_outbox"))
    private NotificationOutbox outbox;

    @Column(name = "push_registration_id", nullable = false)
    private Long pushRegistrationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationTargetStatus status;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(length = 36)
    private String claimToken;

    @Column(length = 80)
    private String lastErrorCode;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column
    private Instant sentAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected NotificationDeliveryTarget() {
    }

    public NotificationDeliveryTarget(NotificationOutbox outbox, Long pushRegistrationId, Instant createdAt) {
        this.outbox = outbox;
        this.pushRegistrationId = pushRegistrationId;
        this.status = NotificationTargetStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = createdAt;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void markSent(Instant now) {
        this.status = NotificationTargetStatus.SENT;
        this.claimToken = null;
        this.lastErrorCode = null;
        this.updatedAt = now;
        this.sentAt = now;
    }

    public void markInvalid(String errorCode, Instant now) {
        this.status = NotificationTargetStatus.INVALID;
        this.claimToken = null;
        this.lastErrorCode = errorCode;
        this.updatedAt = now;
    }

    public void markDead(String errorCode, Instant now) {
        this.status = NotificationTargetStatus.DEAD;
        this.claimToken = null;
        this.lastErrorCode = errorCode;
        this.updatedAt = now;
    }

    public void scheduleRetry(String errorCode, Instant nextAttemptAt, Instant now) {
        this.status = NotificationTargetStatus.RETRY;
        this.claimToken = null;
        this.lastErrorCode = errorCode;
        this.nextAttemptAt = nextAttemptAt;
        this.updatedAt = now;
    }

    public void cancelOwnership(Instant now) {
        this.status = NotificationTargetStatus.CANCELLED_OWNERSHIP;
        this.claimToken = null;
        this.lastErrorCode = "OWNERSHIP_CHANGED";
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public NotificationOutbox getOutbox() {
        return outbox;
    }

    public Long getPushRegistrationId() {
        return pushRegistrationId;
    }

    public NotificationTargetStatus getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void claim(String claimToken, Instant leaseUntil, Instant now) {
        this.attemptCount++;
        this.status = NotificationTargetStatus.PROCESSING;
        this.claimToken = claimToken;
        this.nextAttemptAt = leaseUntil;
        this.updatedAt = now;
    }

    public boolean belongsToClaim(String expectedClaimToken) {
        return this.status == NotificationTargetStatus.PROCESSING
                && java.util.Objects.equals(this.claimToken, expectedClaimToken);
    }
}
