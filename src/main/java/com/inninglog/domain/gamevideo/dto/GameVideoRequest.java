package com.inninglog.domain.gamevideo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record GameVideoRequest(
        @Schema(description = "함께 넣을 친구 userId. 비우면 내 영상만 넣습니다. 친구 목록 API에 나온 친구만 가능합니다.", nullable = true)
        List<Long> friendIds
) {}
