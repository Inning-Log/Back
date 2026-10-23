package com.inninglog.domain.mypage.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ProfileImageUploadResponse(UUID uploadId, String uploadUrl, String method,
        Map<String, String> headers, Instant expiresAt, String profileImageUrl) {}
