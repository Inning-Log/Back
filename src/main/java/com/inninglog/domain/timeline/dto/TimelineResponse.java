package com.inninglog.domain.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.game.dto.GameResponse;
import com.inninglog.domain.game.model.ViewingType;
import java.time.LocalDate;
import java.util.List;

public record TimelineResponse(
        LocalDate today,
        String timezone,
        Owner owner,
        @Schema(description = "경기 정보. 내부 myViewing·recordingState·recordCount는 인증 사용자 본인 기준") GameResponse game,
        @Schema(description = "타임라인 소유자의 관람 등록. 미등록이면 null", nullable = true) Viewing viewing,
        @Schema(description = "현재 경기 이닝. 관측 전 null", nullable = true) Integer currentInning,
        @Schema(description = "현재 경기 초/말. 관측 전 null", nullable = true) String currentHalf,
        boolean canCreateRecord,
        boolean requiresViewingRegistration,
        @Schema(description = "타임라인 소유자의 활성 기록 전체 개수") long recordCount,
        List<InningRecordResponse> records,
        @Schema(description = "다음 페이지 커서. 마지막 페이지면 null", nullable = true) String nextCursor
) {
    public record Owner(
            long userId,
            String username,
            String nickname,
            @Schema(description = "프로필 사진 미설정 시 null", nullable = true) String profileImageUrl
    ) {}
    public record Viewing(long id, ViewingType viewingType, long cheeringTeamId) {}
}
