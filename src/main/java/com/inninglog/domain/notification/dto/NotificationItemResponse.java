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
        @Schema(description = "모든 값은 문자열. audienceUserId는 실제 알림 수신자 ID이며 푸시와 동일. 댓글 알림의 recipientUserId도 같은 수신자이고 ownerUserId는 영상 소유자, actorUserId는 댓글 작성자. link는 설정된 프론트 HTTPS 절대 URL이며 미설정 시 생략. 댓글 이동 시 gameId·recordId·videoId·commentId를 함께 사용. 기존 알림도 조회 시 수신자와 이동 경로 보정") Map<String, String> data,
        @Schema(description = "읽은 시각. 미읽음이면 null. 친구 신청 처리 완료 시 해당 신청 알림도 읽음 처리", nullable = true) Instant readAt,
        Instant createdAt
) {
}
