package com.inninglog.domain.home.controller;

import com.inninglog.domain.home.dto.CalendarResponse;
import com.inninglog.domain.home.dto.WinRateResponse;
import com.inninglog.domain.home.service.HomeService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.game.controller.GameExceptionHandler;
import io.swagger.v3.oas.annotations.Operation;
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
@Tag(name = "홈", description = "월별 달력과 현재 연도의 정규시즌·포스트시즌 직관 승률")
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
    @Operation(summary = "현재 시즌 직관 승률 조회", description = "KST 현재 연도 STADIUM 등록을 집계합니다. 최상위 필드는 정규시즌이며 postseason은 와일드카드·준플레이오프·플레이오프·한국시리즈의 별도 합계입니다. 본인의 포스트시즌 직관 등록이 없으면 postseason은 null. 각 승률은 승/(승+패), 분모가 0이면 null이며 달력 조회 월과 독립적입니다. 시범경기는 제외합니다.")
    public WinRateResponse winRate(JwtAuthenticationToken auth) { return service.winRate(auth.getName()); }
}
