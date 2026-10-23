package com.inninglog.domain.comment.dto;

import java.util.List;

public record CommentPageResponse(List<CommentResponse> items, long totalCount, String nextCursor, boolean hasNext) {}
