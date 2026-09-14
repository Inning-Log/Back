package com.inninglog.domain.timeline.controller;

import com.inninglog.domain.timeline.dto.TimelineProfilesResponse;
import com.inninglog.domain.timeline.dto.TimelineResponse;
import com.inninglog.domain.timeline.service.TimelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/timelines")
@Validated
@Tag(name = "타임라인", description = "경기 ID로 조회하는 본인·수락한 친구의 이닝 기록")
@ApiResponse(responseCode = "200", description = "조회 성공")
@ApiResponse(responseCode = "400", description = "경기 ID, 페이지 크기 또는 커서 오류")
@ApiResponse(responseCode = "401", description = "인증 필요")
@ApiResponse(responseCode = "404", description = "대상 없음 또는 친구 열람 권한 없음")
@ApiResponse(responseCode = "409", description = "온보딩 미완료")
public class TimelineController {
    private final TimelineService service;
    public TimelineController(TimelineService service) { this.service = service; }

    @GetMapping("/me")
    @Operation(summary = "내 경기 타임라인", description = "gameId는 필수입니다. 날짜로 경기를 임의 선택하지 않습니다. records는 이닝·촬영 시각·ID 순이며 nextCursor로 다음 페이지를 조회합니다.")
    public TimelineResponse mine(JwtAuthenticationToken auth, @RequestParam @Positive long gameId,
                                 @RequestParam(required = false) @Size(max = 256) String cursor,
                                 @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.timeline(auth.getName(), null, gameId, cursor, limit);
    }

    @GetMapping("/{userId}")
    @Operation(summary = "친구의 경기 타임라인", description = "userId는 숫자 사용자 ID입니다. 본인 또는 현재 ACCEPTED 친구만 열람할 수 있습니다.")
    public TimelineResponse friend(JwtAuthenticationToken auth, @PathVariable @Positive long userId,
                                   @RequestParam @Positive long gameId,
                                   @RequestParam(required = false) @Size(max = 256) String cursor,
                                   @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.timeline(auth.getName(), userId, gameId, cursor, limit);
    }

    @GetMapping("/profiles")
    @Operation(summary = "오늘의 타임라인 프로필", description = "내 프로필과 수락한 친구 목록, 각 사용자의 오늘 관람 경기 ID와 활성 기록 수를 반환합니다. 친구는 userId 순으로 페이지 처리합니다.")
    public TimelineProfilesResponse profiles(JwtAuthenticationToken auth,
                                             @RequestParam(defaultValue = "0") @PositiveOrZero long afterUserId,
                                             @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.profiles(auth.getName(), afterUserId, limit);
    }
}
