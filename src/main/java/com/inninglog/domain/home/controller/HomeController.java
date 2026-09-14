package com.inninglog.domain.home.controller;

import com.inninglog.domain.home.dto.CalendarResponse;
import com.inninglog.domain.home.dto.WinRateResponse;
import com.inninglog.domain.home.service.HomeService;
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
@Tag(name = "홈", description = "월별 달력과 현재 정규시즌 누적 직관 승률")
@ApiResponse(responseCode = "200", description = "조회 성공")
@ApiResponse(responseCode = "400", description = "연월 요청 오류")
@ApiResponse(responseCode = "401", description = "인증 필요")
@ApiResponse(responseCode = "409", description = "온보딩 미완료 또는 탈퇴한 계정")
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
    @Operation(summary = "현재 시즌 직관 승률 조회", description = "KST 현재 연도 정규시즌의 직관만 집계합니다. 승/(승+패), 분모가 0이면 null. 달력 조회 월과 독립적입니다.")
    public WinRateResponse winRate(JwtAuthenticationToken auth) { return service.winRate(auth.getName()); }
}
