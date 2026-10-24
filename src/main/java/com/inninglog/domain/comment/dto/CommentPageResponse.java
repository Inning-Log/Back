package com.inninglog.domain.comment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record CommentPageResponse(
        List<CommentResponse> items,
        @Schema(description = "삭제되지 않은 원댓글과 답글의 전체 개수. 현재 페이지 크기와 다름") long totalCount,
        @Schema(description = "다음 페이지 커서. 마지막 페이지면 null. 동일 영상에서만 재사용", nullable = true) String nextCursor,
        boolean hasNext
) {}
