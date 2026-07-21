package com.inninglog.domain.team.entity;

import com.inninglog.domain.stadium.entity.Stadium;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(
        name = "kbo_teams",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_kbo_teams_team_code", columnNames = "team_code"),
                @UniqueConstraint(name = "uk_kbo_teams_name", columnNames = "name")
        },
        indexes = {
                @Index(name = "idx_kbo_teams_display_order", columnList = "display_order"),
                @Index(name = "idx_kbo_teams_active", columnList = "active")
        }
)
public class KboTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "team_code", nullable = false, length = 10)
    private String teamCode;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "short_name", nullable = false, length = 20)
    private String shortName;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "primary_color", length = 20)
    private String primaryColor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "home_stadium_id",
            foreignKey = @ForeignKey(name = "fk_kbo_teams_home_stadium")
    )
    private Stadium homeStadium;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KboTeam() {
    }

    public KboTeam(
            String teamCode,
            String name,
            String shortName,
            String logoUrl,
            String primaryColor,
            Stadium homeStadium,
            Integer displayOrder,
            boolean active
    ) {
        this.teamCode = normalizeTeamCode(teamCode);
        this.name = name;
        this.shortName = shortName;
        this.logoUrl = logoUrl;
        this.primaryColor = primaryColor;
        this.homeStadium = homeStadium;
        this.displayOrder = displayOrder;
        this.active = active;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.teamCode = normalizeTeamCode(teamCode);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.teamCode = normalizeTeamCode(teamCode);
        this.updatedAt = Instant.now();
    }

    private static String normalizeTeamCode(String teamCode) {
        if (teamCode == null) {
            return null;
        }
        return teamCode.trim().toUpperCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public String getTeamCode() {
        return teamCode;
    }

    public String getName() {
        return name;
    }

    public String getShortName() {
        return shortName;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public String getPrimaryColor() {
        return primaryColor;
    }

    public Stadium getHomeStadium() {
        return homeStadium;
    }

    public Long getHomeStadiumId() {
        return homeStadium == null ? null : homeStadium.getId();
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public boolean isActiveTeam() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
