package com.inninglog.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "notification_settings")
public class NotificationSetting {

    @Id
    private Long userId;

    @Column(nullable = false)
    private boolean gameProgressEnabled;

    @Column(nullable = false)
    private boolean recordReminderEnabled;

    @Column(nullable = false)
    private boolean socialReactionEnabled;

    @Column(nullable = false)
    private Instant updatedAt;

    protected NotificationSetting() {
    }

    public NotificationSetting(Long userId, Instant now) {
        this.userId = userId;
        this.gameProgressEnabled = true;
        this.recordReminderEnabled = true;
        this.socialReactionEnabled = true;
        this.updatedAt = now;
    }

    public void update(
            Boolean gameProgressEnabled,
            Boolean recordReminderEnabled,
            Boolean socialReactionEnabled,
            Instant now
    ) {
        if (gameProgressEnabled != null) {
            this.gameProgressEnabled = gameProgressEnabled;
        }
        if (recordReminderEnabled != null) {
            this.recordReminderEnabled = recordReminderEnabled;
        }
        if (socialReactionEnabled != null) {
            this.socialReactionEnabled = socialReactionEnabled;
        }
        this.updatedAt = now;
    }

    public Long getUserId() {
        return userId;
    }

    public boolean isGameProgressEnabled() {
        return gameProgressEnabled;
    }

    public boolean isRecordReminderEnabled() {
        return recordReminderEnabled;
    }

    public boolean isSocialReactionEnabled() {
        return socialReactionEnabled;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
