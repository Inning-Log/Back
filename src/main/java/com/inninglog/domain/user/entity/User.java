package com.inninglog.domain.user.entity;

import com.inninglog.domain.team.entity.KboTeam;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(unique = true, length = 80)
    private String username;

    @Column(length = 80)
    private String nickname;

    @Column(length = 500)
    private String profileImageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "favorite_team_id",
            foreignKey = @ForeignKey(name = "fk_app_users_favorite_team")
    )
    private KboTeam favoriteTeam;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserRole role;

    @Column(nullable = false)
    private boolean onboardingCompleted;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public User(String email, String profileImageUrl) {
        this.email = email;
        this.profileImageUrl = profileImageUrl;
        this.role = UserRole.USER;
        this.onboardingCompleted = false;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void updateGoogleProfile(String email, String profileImageUrl) {
        this.email = email;
        this.profileImageUrl = profileImageUrl;
    }

    public void setupProfile(String username, String nickname) {
        this.username = username;
        this.nickname = nickname;
    }

    public void selectInitialFavoriteTeam(KboTeam favoriteTeam) {
        if (username == null || username.isBlank() || nickname == null || nickname.isBlank()) {
            throw new ProfileSetupRequiredException();
        }
        if (this.favoriteTeam != null) {
            throw new FavoriteTeamAlreadySelectedException();
        }
        this.favoriteTeam = favoriteTeam;
        this.onboardingCompleted = true;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getUsername() {
        return username;
    }

    public String getNickname() {
        return nickname;
    }

    public String getProfileImageUrl() {
        return profileImageUrl;
    }

    public KboTeam getFavoriteTeam() {
        return favoriteTeam;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isOnboardingCompleted() {
        return onboardingCompleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
