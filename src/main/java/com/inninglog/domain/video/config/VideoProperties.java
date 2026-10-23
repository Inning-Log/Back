package com.inninglog.domain.video.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("app.videos")
public record VideoProperties(
        boolean enabled, String bucket, String region, String keyPrefix,
        Duration uploadUrlTtl, Duration downloadUrlTtl, DataSize maxSize, List<String> allowedContentTypes
) {
    public VideoProperties {
        if (enabled && (bucket == null || bucket.isBlank()))
            throw new IllegalArgumentException("app.videos.bucket must be configured when video storage is enabled.");
        if (region == null || region.isBlank()) region = "ap-northeast-2";
        if (keyPrefix == null || keyPrefix.isBlank()) keyPrefix = "inning-records";
        keyPrefix = keyPrefix.replaceAll("^/+|/+$", "");
        if (uploadUrlTtl == null) uploadUrlTtl = Duration.ofMinutes(10);
        if (downloadUrlTtl == null) downloadUrlTtl = Duration.ofMinutes(10);
        if (maxSize == null) maxSize = DataSize.ofMegabytes(500);
        if (allowedContentTypes == null || allowedContentTypes.isEmpty())
            allowedContentTypes = List.of("video/mp4", "video/quicktime", "video/webm");
        allowedContentTypes = allowedContentTypes.stream().map(String::trim).filter(type -> !type.isEmpty()).toList();
    }
}
