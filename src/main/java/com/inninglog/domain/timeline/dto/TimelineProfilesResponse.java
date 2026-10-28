package com.inninglog.domain.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.game.model.ViewingType;
import java.time.LocalDate;
import java.util.List;

public record TimelineProfilesResponse(
        @Schema(description = "KST 달력 날짜. 경기 선택은 games를 사용하고 리셋 기준은 timelineDate·nextResetAt을 사용") LocalDate today,
        Profile me,
        List<Profile> friends,
        @Schema(description = "다음 친구 페이지의 afterUserId. 마지막 페이지면 null", nullable = true) Long nextAfterUserId,
        @Schema(description = "현재 타임라인에 유지하는 가장 이른 경기일(KST)") LocalDate timelineDate,
        @Schema(description = "전 구단 공통 다음 화면 리셋 시각. 첫 경기 시작 30분 전이며 경기가 없으면 KST 18:30. 이때 프로필 목록 재조회. 기록 작성 시간은 별개") java.time.Instant nextResetAt
) {
    public record Profile(
            long userId,
            String username,
            String nickname,
            @Schema(description = "프로필 사진 미설정 시 null", nullable = true) String profileImageUrl,
            @Schema(description = "필드명은 호환성을 위해 유지. 현재 타임라인 기간(timelineDate부터 today)에 활성 기록이 있는지 여부이며 영상 READY 여부는 아님") boolean hasRecordedToday,
            @Schema(description = "현재 타임라인 기간의 관람 등록 경기. 리셋 전에는 전날 경기를 포함할 수 있음") List<GameSummary> games
    ) {}
    public record GameSummary(long gameId, ViewingType viewingType, long recordCount) {}
}
