package com.inninglog.domain.gamevideo.service;

import com.inninglog.domain.gamevideo.config.GameVideoProperties;
import com.inninglog.domain.gamevideo.repository.GameVideoRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net for lost messages: a job that was never queued, a worker that crashed past its retries
 * (its message is then in the DLQ) or a result that never arrived.
 */
@Component
@ConditionalOnProperty(prefix = "app.game-videos", name = "enabled", havingValue = "true")
public class GameVideoRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(GameVideoRecoveryScheduler.class);
    private final GameVideoRepository videos;
    private final GameVideoJobs jobs;
    private final GameVideoProperties properties;
    private final Clock clock;

    public GameVideoRecoveryScheduler(GameVideoRepository videos, GameVideoJobs jobs, GameVideoProperties properties, Clock clock) {
        this.videos = videos;
        this.jobs = jobs;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(scheduler = "gameVideoScheduler", fixedDelayString = "${app.game-videos.recovery-delay:1m}",
            initialDelayString = "${app.game-videos.recovery-initial-delay:1m}")
    public void recover() {
        Instant now = clock.instant();
        for (String renderKey : videos.processingRenderKeysRequestedBefore(now.minus(properties.segmentTimeout())))
            run(() -> jobs.segmentFailed(renderKey, "TIMEOUT"));
        for (long videoId : videos.renderingWithAllSegmentsReady())
            run(() -> jobs.startMergeIfReady(videoId));
        for (long videoId : videos.mergingRequestedBefore(now.minus(properties.mergeTimeout())))
            run(() -> jobs.mergeFailed(videoId, "TIMEOUT"));
    }

    private static void run(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException error) {
            log.warn("Game video recovery step failed ({})", error.getClass().getSimpleName());
        }
    }
}
