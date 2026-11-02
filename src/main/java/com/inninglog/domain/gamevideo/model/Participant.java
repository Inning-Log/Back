package com.inninglog.domain.gamevideo.model;

/** A user's profile and cheering team for one game. */
public record Participant(
        long userId, String username, String nickname, String profileImageUrl,
        String teamCode, String teamName, long clipCount
) {
    public String displayName() {
        if (nickname != null && !nickname.isBlank()) return nickname;
        return username == null ? "" : username;
    }
}
