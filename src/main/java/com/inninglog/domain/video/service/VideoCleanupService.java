package com.inninglog.domain.video.service;

import com.inninglog.domain.video.model.VideoStatus;
import com.inninglog.domain.video.repository.VideoRepository;
import com.inninglog.domain.video.storage.VideoStorage;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VideoCleanupService {
    // Allow uploads started before URL expiry to finish, including slow 500 MB uploads.
    public static final Duration COMPLETION_GRACE = Duration.ofHours(1);
    private static final Logger log = LoggerFactory.getLogger(VideoCleanupService.class);
    private final VideoRepository videos;
    private final VideoStorage storage;
    private final Clock clock;

    public VideoCleanupService(VideoRepository videos, VideoStorage storage, Clock clock) {
        this.videos = videos; this.storage = storage; this.clock = clock;
    }

    public List<Long> candidates() {
        return videos.cleanupCandidates(clock.instant(), clock.instant().minus(COMPLETION_GRACE));
    }

    @Transactional
    public void cleanup(long id) {
        var initial = videos.cleanupRow(id).orElse(null);
        if (initial == null) return;
        videos.lockForCleanup(initial);
        var row = videos.cleanupRow(id).orElseThrow();
        var now = clock.instant();
        if (row.storageDeletedAt() != null || (row.retryAt() != null && row.retryAt().isAfter(now))) return;
        var video = row.video();
        var safeAfter = video.uploadExpiresAt().plus(COMPLETION_GRACE);
        boolean expired = video.status() == VideoStatus.PENDING && !safeAfter.isAfter(now);
        if (video.deletedAt() == null && !row.inactiveParent() && !expired) return;
        videos.delete(id, now);
        try {
            storage.delete(video.objectKey());
        } catch (RuntimeException error) {
            long minutes = Math.min(360, 5L << Math.min(row.attempts(), 7));
            videos.retryCleanup(id, now.plus(Duration.ofMinutes(minutes)), true);
            // Never log SDK URLs, response bodies or credentials.
            log.warn("Video storage cleanup will retry video {}", id);
            return;
        }
        // Even if an early delete succeeded, an unexpired PUT URL can recreate the object.
        if (safeAfter.isAfter(now)) videos.retryCleanup(id, safeAfter, false);
        else videos.storageDeleted(id, now);
    }
}
