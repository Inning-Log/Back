package com.inninglog.domain.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.game.dto.GameResponse;
import com.inninglog.domain.game.model.ViewingType;
import java.time.LocalDate;
import java.util.List;

public record TimelineResponse(
        @Schema(description = "KST 달력 날짜. 리셋 전에는 timelineDate와 다를 수 있음") LocalDate today,
        String timezone,
        Owner owner,
        @Schema(description = "경기 정보. 내부 myViewing·recordingState·recordCount는 인증 사용자 본인 기준") GameResponse game,
        @Schema(description = "타임라인 소유자의 관람 등록. 미등록이면 null", nullable = true) Viewing viewing,
        @Schema(description = "현재 경기 이닝. 관측 전 null", nullable = true) Integer currentInning,
        @Schema(description = "현재 경기 초/말. 관측 전 null", nullable = true) String currentHalf,
        @Schema(description = "본인 타임라인이며 관람 등록이 있고 기록 가능한 경기인지 여부. 화면 리셋과 독립적으로 전날 경기는 당일 첫 경기 시작 전까지 작성 가능") boolean canCreateRecord,
        @Schema(description = "본인 타임라인의 기록 가능한 경기에 관람 등록이 필요한지 여부") boolean requiresViewingRegistration,
        @Schema(description = "타임라인 소유자의 활성 기록 전체 개수") long recordCount,
        List<InningRecordResponse> records,
        @Schema(description = "다음 페이지 커서. 마지막 페이지면 null", nullable = true) String nextCursor,
        @Schema(description = "리그 공통 리셋 기준의 타임라인 날짜. 리셋 전에는 KST 전날") LocalDate timelineDate,
        @Schema(description = "다음 화면 리셋 시각(UTC). 전체 경기 중 가장 이른 시작 30분 전, 전 경기 없음이면 KST 18:30 유지. 기록 작성 마감 시각과 별개") java.time.Instant nextResetAt
) {
    public record Owner(
            long userId,
            String username,
            String nickname,
            @Schema(description = "프로필 사진 미설정 시 null", nullable = true) String profileImageUrl
    ) {}
    public record Viewing(long id, ViewingType viewingType, long cheeringTeamId) {}
}
