package com.inninglog.domain.video.dto;

import java.time.Instant;
import java.util.Map;

public record VideoUploadResponse(long videoId, long recordId, String uploadUrl, String method,
                                  Map<String, String> headers, Instant expiresAt) {}
