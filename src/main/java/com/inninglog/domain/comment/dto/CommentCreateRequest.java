package com.inninglog.domain.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CommentCreateRequest(
        @Schema(description = "댓글 작성 시 생성한 UUID. 동일 댓글 재시도는 같은 UUID·본문·parentCommentId 사용") @NotNull UUID clientCommentId,
        @NotBlank @Size(max = 1000) String content,
        @Positive @Schema(description = "생략 또는 null이면 원댓글. 지정 시 같은 영상의 원댓글 ID만 허용. 답글 ID는 400 COMMENT_REPLY_DEPTH_EXCEEDED", nullable = true) Long parentCommentId
) {
    public CommentCreateRequest(UUID clientCommentId, String content) {
        this(clientCommentId, content, null);
    }
}
