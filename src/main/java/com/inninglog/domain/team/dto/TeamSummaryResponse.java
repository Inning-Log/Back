package com.inninglog.domain.team.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.team.entity.KboTeam;

public record TeamSummaryResponse(
        Long id,
        String teamCode,
        String name,
        String shortName,
        @Schema(description = "로고 미등록 시 null", nullable = true) String logoUrl,
        @Schema(description = "대표 색상 미등록 시 null", nullable = true) String primaryColor,
        Integer displayOrder
) {

    public static TeamSummaryResponse from(KboTeam team) {
        return new TeamSummaryResponse(
                team.getId(),
                team.getTeamCode(),
                team.getName(),
                team.getShortName(),
                team.getLogoUrl(),
                team.getPrimaryColor(),
                team.getDisplayOrder());
    }
}
