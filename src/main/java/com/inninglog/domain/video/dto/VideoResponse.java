package com.inninglog.domain.video.dto;

import com.inninglog.domain.video.model.InningRecordVideo;
import com.inninglog.domain.video.model.VideoStatus;
import java.time.Instant;

/** downloadUrl and expiresAt are present only when the video is READY. */
public record VideoResponse(long videoId, long recordId, VideoStatus status, String contentType, long sizeBytes,
                            String downloadUrl, Instant expiresAt, Instant uploadedAt) {
    public static VideoResponse from(InningRecordVideo video, String downloadUrl, Instant expiresAt) {
        return new VideoResponse(video.id(), video.inningRecordId(), video.status(), video.contentType(), video.sizeBytes(),
                downloadUrl, expiresAt, video.uploadedAt());
    }
}
