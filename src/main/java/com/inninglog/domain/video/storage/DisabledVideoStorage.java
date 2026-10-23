package com.inninglog.domain.video.storage;

import com.inninglog.domain.game.exception.GameException;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.videos", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledVideoStorage implements VideoStorage {

    @Override
    public PresignedUrl presignUpload(String key, String contentType, long contentLength, Duration ttl) {
        throw disabled();
    }

    @Override
    public PresignedUrl presignDownload(String key, Duration ttl) {
        throw disabled();
    }

    @Override
    public Optional<StoredObject> head(String key) {
        throw disabled();
    }

    @Override
    public void delete(String key) {
        // Storage may have been disabled after uploads existed. Never acknowledge a deletion we did not perform.
        throw disabled();
    }

    private static GameException disabled() {
        return new GameException(HttpStatus.SERVICE_UNAVAILABLE, "VIDEO_STORAGE_DISABLED", "영상 저장소가 설정되지 않았습니다.");
    }
}
