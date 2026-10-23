package com.inninglog.domain.video.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.videos", name = "enabled", havingValue = "true")
public class VideoCleanupScheduler {
    private static final Logger log = LoggerFactory.getLogger(VideoCleanupScheduler.class);
    private final VideoCleanupService cleanup;
    public VideoCleanupScheduler(VideoCleanupService cleanup) { this.cleanup = cleanup; }

    @Scheduled(fixedDelayString = "${app.videos.cleanup-delay:PT10M}",
            initialDelayString = "${app.videos.cleanup-initial-delay:PT2M}")
    public void run() {
        for (long id : cleanup.candidates()) {
            try { cleanup.cleanup(id); }
            catch (RuntimeException error) { log.warn("Video cleanup transaction will retry video {}", id); }
        }
    }
}
