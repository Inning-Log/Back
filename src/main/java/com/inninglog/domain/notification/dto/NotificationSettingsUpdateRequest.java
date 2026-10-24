package com.inninglog.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record NotificationSettingsUpdateRequest(
        @Schema(description = "경기 진행 알림. 생략·null이면 기존 설정 유지", nullable = true) Boolean gameProgressEnabled,
        @Schema(description = "기록 리마인더. 생략·null이면 기존 설정 유지", nullable = true) Boolean recordReminderEnabled,
        @Schema(description = "댓글·답글 등 소셜 알림. 생략·null이면 기존 설정 유지. 최소 한 설정은 지정해야 함", nullable = true) Boolean socialReactionEnabled
) {
    public boolean isEmpty() {
        return gameProgressEnabled == null
                && recordReminderEnabled == null
                && socialReactionEnabled == null;
    }
}
