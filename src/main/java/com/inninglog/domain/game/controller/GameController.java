package com.inninglog.domain.game.controller;

import com.inninglog.domain.game.dto.GameListResponse;
import com.inninglog.domain.game.dto.GameStateHistoryResponse;
import com.inninglog.domain.game.service.GameService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games")
@Validated
@Tag(name = "경기", description = "날짜별 전체 경기 목록")
@ApiResponse(responseCode = "409", description = "온보딩 미완료 또는 탈퇴한 계정",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
public class GameController {
    private final GameService service;
    public GameController(GameService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "날짜별 경기 조회", description = "경기 정보와 내 관람 등록 여부를 반환합니다. 아직 수입되지 않은 일정은 NOT_IMPORTED입니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "날짜 요청 오류",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "인증 필요",
        content = @Content)
    @ApiResponse(responseCode = "404", description = "사용자 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
    public GameListResponse list(JwtAuthenticationToken auth,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.forDate(auth.getName(), date);
    }

    @GetMapping("/{gameId}/states")
    @Operation(summary = "경기 상태 변화 이력 조회", description = "점수·이닝·초/말·상태가 달라진 시점만 시간순으로 반환합니다.")
    @ApiResponse(responseCode = "400", description = "경기 ID 요청 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "인증 필요",
        content = @Content)
    @ApiResponse(responseCode = "404", description = "경기 없음",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
    public GameStateHistoryResponse stateHistory(JwtAuthenticationToken auth, @PathVariable @Positive long gameId) {
        return service.stateHistory(auth.getName(), gameId);
    }
}
