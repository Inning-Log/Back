package com.inninglog.domain.mypage.image;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("app.profile-images")
public record ProfileImageProperties(DataSize maxSize, Duration uploadUrlTtl, Duration downloadUrlTtl) {
    public ProfileImageProperties {
        if (maxSize == null) maxSize = DataSize.ofMegabytes(10);
        if (uploadUrlTtl == null) uploadUrlTtl = Duration.ofMinutes(5);
        if (downloadUrlTtl == null) downloadUrlTtl = Duration.ofMinutes(5);
        if (maxSize.toBytes() < 1 || maxSize.toBytes() > DataSize.ofMegabytes(10).toBytes())
            throw new IllegalArgumentException("Profile image size must be between 1 byte and 10 MB.");
        if (uploadUrlTtl.isNegative() || uploadUrlTtl.isZero() || uploadUrlTtl.compareTo(Duration.ofMinutes(15)) > 0
                || downloadUrlTtl.isNegative() || downloadUrlTtl.isZero() || downloadUrlTtl.compareTo(Duration.ofMinutes(15)) > 0)
            throw new IllegalArgumentException("Profile image URL TTL must be within 15 minutes.");
    }
}
