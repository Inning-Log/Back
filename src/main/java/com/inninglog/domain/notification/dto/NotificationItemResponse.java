package com.inninglog.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.notification.entity.NotificationType;
import java.time.Instant;
import java.util.Map;

public record NotificationItemResponse(
        Long id,
        NotificationType type,
        String title,
        String body,
        @Schema(description = "알림 이동 정보. link는 앱 경로. 유형에 따라 friendshipId, actorUserId, ownerUserId, gameId, recordId, videoId, commentId 등이 문자열로 제공됨") Map<String, String> data,
        @Schema(description = "읽은 시각. 미읽음이면 null. 친구 신청 처리 완료 시 해당 신청 알림도 읽음 처리", nullable = true) Instant readAt,
        Instant createdAt
) {
}
