package com.inninglog.domain.notification.entity;

import com.inninglog.domain.user.entity.User;
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
import java.time.Instant;

@Entity
@Table(
        name = "user_push_tokens",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_user_push_tokens_push_token", columnNames = "push_token"),
                @UniqueConstraint(name = "uk_user_push_tokens_user_device", columnNames = {"user_id", "device_id"})
        }
)
public class UserPushToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_user_push_tokens_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DevicePlatform platform;

    @Column(nullable = false, length = 255)
    private String deviceId;

    @Column(nullable = false, unique = true, length = 500)
    private String pushToken;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private Instant lastSeenAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected UserPushToken() {
    }

    public UserPushToken(
            User user,
            DevicePlatform platform,
            String deviceId,
            String pushToken,
            Instant registeredAt
    ) {
        this.user = user;
        this.platform = platform;
        this.deviceId = deviceId;
        this.pushToken = pushToken;
        this.enabled = true;
        this.lastSeenAt = registeredAt;
        this.createdAt = registeredAt;
        this.updatedAt = registeredAt;
    }

    public void register(
            User user,
            DevicePlatform platform,
            String deviceId,
            String pushToken,
            Instant registeredAt
    ) {
        this.user = user;
        this.platform = platform;
        this.deviceId = deviceId;
        this.pushToken = pushToken;
        this.enabled = true;
        this.lastSeenAt = registeredAt;
        this.updatedAt = registeredAt;
    }

    public void disable(Instant disabledAt) {
        this.enabled = false;
        this.updatedAt = disabledAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return user.getId();
    }

    public DevicePlatform getPlatform() {
        return platform;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public String getPushToken() {
        return pushToken;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
