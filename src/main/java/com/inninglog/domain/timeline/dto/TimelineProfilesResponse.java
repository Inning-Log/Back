package com.inninglog.domain.timeline.dto;

import com.inninglog.domain.game.model.ViewingType;
import java.time.LocalDate;
import java.util.List;

public record TimelineProfilesResponse(
        LocalDate today, Profile me, List<Profile> friends, Long nextAfterUserId
) {
    public record Profile(long userId, String username, String nickname, String profileImageUrl,
                          boolean hasRecordedToday, List<GameSummary> games) {}
    public record GameSummary(long gameId, ViewingType viewingType, long recordCount) {}
}
