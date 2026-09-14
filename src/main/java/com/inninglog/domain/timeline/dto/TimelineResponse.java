package com.inninglog.domain.timeline.dto;

import com.inninglog.domain.game.dto.GameResponse;
import com.inninglog.domain.game.model.ViewingType;
import java.time.LocalDate;
import java.util.List;

public record TimelineResponse(
        LocalDate today, String timezone, Owner owner, GameResponse game,
        Viewing viewing, Integer currentInning, String currentHalf,
        boolean canCreateRecord, boolean requiresViewingRegistration,
        long recordCount, List<InningRecordResponse> records, String nextCursor
) {
    public record Owner(long userId, String username, String nickname, String profileImageUrl) {}
    public record Viewing(long id, ViewingType viewingType, long cheeringTeamId) {}
}
