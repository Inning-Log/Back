package com.inninglog.domain.video.model;

import java.time.Instant;

public record InningRecordVideo(
        long id, long inningRecordId, String objectKey, String contentType, long sizeBytes, VideoStatus status,
        Instant createdAt, Instant updatedAt, Instant uploadedAt, Instant deletedAt
) {}
