package com.inninglog.domain.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.video.model.InningRecordVideo;
import com.inninglog.domain.video.model.VideoStatus;
import java.time.Instant;

/** downloadUrl and expiresAt are present only when the video is READY. */
public record VideoResponse(
        long videoId,
        long recordId,
        VideoStatus status,
        String contentType,
        long sizeBytes,
        @Schema(description = "READY일 때만 발급되는 다운로드 URL. PENDING이면 null", nullable = true) String downloadUrl,
        @Schema(description = "다운로드 URL 만료 시각. PENDING이면 null. 만료 시 영상 조회 API로 재발급", nullable = true) Instant expiresAt,
        @Schema(description = "업로드 완료 확인 시각. PENDING이면 null", nullable = true) Instant uploadedAt
) {
    public static VideoResponse from(InningRecordVideo video, String downloadUrl, Instant expiresAt) {
        return new VideoResponse(video.id(), video.inningRecordId(), video.status(), video.contentType(), video.sizeBytes(),
                downloadUrl, expiresAt, video.uploadedAt());
    }
}
