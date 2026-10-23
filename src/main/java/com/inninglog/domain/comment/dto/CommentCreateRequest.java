package com.inninglog.domain.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CommentCreateRequest(@NotNull UUID clientCommentId, @NotBlank @Size(max = 1000) String content,
                                   @Positive Long parentCommentId) {
    public CommentCreateRequest(UUID clientCommentId, String content) {
        this(clientCommentId, content, null);
    }
}
