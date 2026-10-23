package com.inninglog.domain.mypage.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProfileImageCleanup {
    private static final Logger log = LoggerFactory.getLogger(ProfileImageCleanup.class);
    private final ProfileImageService service;
    public ProfileImageCleanup(ProfileImageService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${app.profile-images.cleanup-delay:PT1H}",
            initialDelayString = "${app.profile-images.cleanup-initial-delay:PT10M}")
    public void clean() {
        for (var id : service.cleanupCandidates()) {
            try { service.cleanup(id); }
            catch (RuntimeException e) {
                // Do not log presigned URLs or SDK request details.
                log.warn("Profile image cleanup will retry upload {}", id);
            }
        }
    }
}

