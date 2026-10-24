package com.inninglog.domain.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record CommentResponse(
        long id,
        long videoId,
        Author author,
        String content,
        Instant createdAt,
        Instant updatedAt,
        boolean isMine,
        @Schema(description = "원댓글이면 null. 답글이면 같은 영상의 원댓글 ID. 답글의 답글은 없음", nullable = true) Long parentCommentId
) {
    public record Author(
            long userId,
            String username,
            String nickname,
            @Schema(description = "프로필 사진 미설정 시 null", nullable = true) String profileImageUrl
    ) {}
}
