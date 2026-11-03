package com.inninglog.domain.gamevideo.dto;

import com.inninglog.domain.gamevideo.model.Participant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record GameVideoFriendsResponse(
        @Schema(description = "함께 넣을 수 있는 최대 친구 수") int maxFriends,
        List<Friend> friends
) {
    public record Friend(long userId, String username, String nickname,
                         @Schema(nullable = true) String profileImageUrl,
                         @Schema(description = "이 경기의 응원팀 약칭") String cheeringTeamName,
                         @Schema(description = "이 경기에서 완료된 영상 수") long clipCount) {
        public static Friend from(Participant participant) {
            return new Friend(participant.userId(), participant.username(), participant.nickname(),
                    participant.profileImageUrl(), participant.teamName(), participant.clipCount());
        }
    }
}
