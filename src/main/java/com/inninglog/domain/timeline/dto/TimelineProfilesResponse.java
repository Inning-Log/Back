package com.inninglog.domain.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.game.model.ViewingType;
import java.time.LocalDate;
import java.util.List;

public record TimelineProfilesResponse(
        LocalDate today,
        Profile me,
        List<Profile> friends,
        @Schema(description = "다음 친구 페이지의 afterUserId. 마지막 페이지면 null", nullable = true) Long nextAfterUserId,
        @Schema(description = "현재 타임라인에 유지하는 가장 이른 경기일(KST)") LocalDate timelineDate,
        @Schema(description = "전 구단 공통 다음 리셋 시각. 이때 프로필 목록을 다시 조회") java.time.Instant nextResetAt
) {
    public record Profile(
            long userId,
            String username,
            String nickname,
            @Schema(description = "프로필 사진 미설정 시 null", nullable = true) String profileImageUrl,
            boolean hasRecordedToday,
            List<GameSummary> games
    ) {}
    public record GameSummary(long gameId, ViewingType viewingType, long recordCount) {}
}
