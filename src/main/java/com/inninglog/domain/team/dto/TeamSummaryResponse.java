package com.inninglog.domain.team.dto;

import com.inninglog.domain.team.entity.KboTeam;

public record TeamSummaryResponse(
        Long id,
        String teamCode,
        String name,
        String shortName,
        String logoUrl,
        String primaryColor,
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
