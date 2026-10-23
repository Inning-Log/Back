package com.inninglog.domain.comment.dto;

import java.time.Instant;

public record CommentResponse(long id, long videoId, Author author, String content,
                              Instant createdAt, Instant updatedAt, boolean isMine) {
    public record Author(long userId, String username, String nickname, String profileImageUrl) {}
}
