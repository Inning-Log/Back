package com.inninglog.domain.gamevideo.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Merged game video rendering. The worker (AWS Lambda) reads jobs from {@code jobQueueUrl} and reports to
 * {@code resultQueueUrl}. Disabled by default: the save API then answers 503.
 */
@ConfigurationProperties("app.game-videos")
public record GameVideoProperties(
        boolean enabled, String region, String jobQueueUrl, String resultQueueUrl,
        Duration segmentTimeout, Duration mergeTimeout, int maxFriends,
        String segmentPrefix, String outputPrefix, String templateVersion
) {
    public GameVideoProperties {
        if (region == null || region.isBlank()) region = "ap-northeast-2";
        if (enabled && (blank(jobQueueUrl) || blank(resultQueueUrl)))
            throw new IllegalArgumentException("app.game-videos job and result queue URLs are required when enabled.");
        // Longer than the Lambda timeout (15 min) times its retries, so a slow job is not failed while still running.
        if (segmentTimeout == null) segmentTimeout = Duration.ofMinutes(50);
        if (mergeTimeout == null) mergeTimeout = Duration.ofMinutes(50);
        if (maxFriends <= 0) maxFriends = 5;
        segmentPrefix = trimSlashes(blank(segmentPrefix) ? "segments" : segmentPrefix);
        outputPrefix = trimSlashes(blank(outputPrefix) ? "game-videos" : outputPrefix);
        // Bump when the worker's drawing changes so cached segments are rendered again.
        if (blank(templateVersion)) templateVersion = "v1";
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String trimSlashes(String value) { return value.trim().replaceAll("^/+|/+$", ""); }
}
