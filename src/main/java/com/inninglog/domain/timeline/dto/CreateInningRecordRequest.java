package com.inninglog.domain.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.timeline.model.InningHalf;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public record CreateInningRecordRequest(
        @NotNull @Positive Long gameId,
        @Schema(description = "촬영 기록별 UUID. 네트워크 재시도에는 같은 UUID와 최초 요청 본문 유지") @NotNull UUID clientRecordId,
        @Schema(description = "촬영 당시 이닝. 저장·재시도 시점의 현재 이닝으로 바꾸지 않음") @NotNull @Min(1) @Max(99) Integer inning,
        @Schema(description = "촬영 당시 초/말. 생략 또는 null이면 초/말 미지정으로 저장", nullable = true) InningHalf half,
        @Schema(description = "실제 촬영 시각(UTC ISO-8601). 경기일 KST 00:00 이상, 다음 날 첫 경기 시작 시각 미만(경기 없음: KST 18:30)이며 미래 시각 불가. 화면 리셋 시각과 별개. 저장 버튼을 누른 시각이 아님") @NotNull Instant recordedAt,
        @NotNull @Size(max = 255) String text
) {}
