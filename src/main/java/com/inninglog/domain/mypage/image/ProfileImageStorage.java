package com.inninglog.domain.mypage.image;

import java.time.Duration;
import java.util.UUID;
import com.inninglog.domain.video.storage.VideoStorage.PresignedUrl;

public interface ProfileImageStorage {
    PresignedUrl presignUpload(UUID id, String contentType, long size, Duration ttl);
    byte[] readUpload(UUID id, String contentType, long size);
    void publish(UUID id, byte[] png);
    String downloadUrl(UUID id, Duration ttl);
    void deleteUpload(UUID id);
    void deletePublished(UUID id);
}
