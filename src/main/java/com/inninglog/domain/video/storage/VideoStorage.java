package com.inninglog.domain.video.storage;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Object storage for inning record videos. Clients upload and download directly with presigned URLs. */
public interface VideoStorage {
    PresignedUrl presignUpload(String key, String contentType, long contentLength, Duration ttl);

    PresignedUrl presignDownload(String key, Duration ttl);

    Optional<StoredObject> head(String key);

    void delete(String key);

    record PresignedUrl(String url, String method, Map<String, String> headers, Instant expiresAt) {}

    record StoredObject(long sizeBytes, String contentType) {}
}
