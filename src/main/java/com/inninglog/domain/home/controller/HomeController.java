package com.inninglog.domain.home.controller;

import com.inninglog.domain.home.dto.CalendarResponse;
import com.inninglog.domain.home.dto.WinRateResponse;
import com.inninglog.domain.home.service.HomeService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.game.controller.GameExceptionHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/home")
@Validated
@Tag(name = "홈", description = "월별 달력과 연도별 정규시즌·포스트시즌 직관 승률")
@ApiResponse(responseCode = "404", description = "사용자 없음",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
@ApiResponse(responseCode = "200", description = "조회 성공")
@ApiResponse(responseCode = "400", description = "연월 요청 오류",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
@ApiResponse(responseCode = "401", description = "인증 필요",
        content = @Content)
@ApiResponse(responseCode = "409", description = "온보딩 미완료 또는 탈퇴한 계정",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
public class HomeController {
    private final HomeService service;
    public HomeController(HomeService service) { this.service = service; }

    @GetMapping("/calendar")
    @Operation(summary = "홈 월간 달력 조회", description = "최애팀 일정과 내 관람 경기의 합집합입니다. 더블헤더는 날짜별 games 배열로 반환합니다.")
    public CalendarResponse calendar(JwtAuthenticationToken auth,
            @RequestParam @Min(1900) @Max(9998) int year, @RequestParam @Min(1) @Max(12) int month) {
        return service.calendar(auth.getName(), year, month);
    }

    @GetMapping("/win-rate")
    @Operation(summary = "연도별 직관 승률 조회", description = "year로 선택한 시즌 연도의 STADIUM 등록을 집계하며, 생략하면 KST 현재 연도를 사용합니다. 응답 seasonYear는 실제 집계 연도입니다. 최상위 필드는 같은 연도 정규시즌과 포스트시즌(와일드카드·준플레이오프·플레이오프·한국시리즈)을 합산한 전체 통계입니다. 승패는 기록 당시 cheeringTeamId 기준으로 계산하므로 프로필의 응원팀을 바꿔도 기존 기록의 승률은 유지됩니다. postseason은 전체 통계에 이미 포함된 포스트시즌 상세 집계이므로 다시 더하지 않습니다. 본인의 포스트시즌 직관 등록이 없으면 postseason은 null. 각 승률은 승/(승+패), 분모가 0이면 null이며 달력 조회 월과 독립적입니다. 시범경기는 제외합니다.")
    public WinRateResponse winRate(JwtAuthenticationToken auth,
            @Parameter(description = "조회할 시즌 연도(1900~9998). 생략하면 요청 시점의 KST 현재 연도", example = "2025")
            @RequestParam(required = false) @Min(1900) @Max(9998) Integer year) {
        return service.winRate(auth.getName(), year);
    }
}
