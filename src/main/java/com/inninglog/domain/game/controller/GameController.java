package com.inninglog.domain.game.controller;

import com.inninglog.domain.game.dto.GameListResponse;
import com.inninglog.domain.game.service.GameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games")
@Tag(name = "경기", description = "날짜별 전체 경기 목록")
public class GameController {
    private final GameService service;
    public GameController(GameService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "날짜별 경기 조회", description = "경기 정보와 내 관람 등록 여부를 반환합니다. 아직 수입되지 않은 일정은 NOT_IMPORTED입니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "날짜 요청 오류")
    @ApiResponse(responseCode = "401", description = "인증 필요")
    public GameListResponse list(JwtAuthenticationToken auth,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.forDate(auth.getName(), date);
    }
}
