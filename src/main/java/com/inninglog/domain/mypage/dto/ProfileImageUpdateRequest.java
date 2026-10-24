package com.inninglog.domain.mypage.dto;

import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record ProfileImageUpdateRequest(
        @Schema(description = "서버가 발급한 프로필 이미지 경로. null 또는 빈 문자열이면 기본 사진으로 초기화", nullable = true)
        @Size(max = 500) String profileImageUrl
) {
}
