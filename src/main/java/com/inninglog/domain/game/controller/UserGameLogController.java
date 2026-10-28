package com.inninglog.domain.game.controller;

import com.inninglog.domain.game.dto.*;
import com.inninglog.domain.game.service.GameService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user-game-logs")
@Validated
@Tag(name = "관람 기록", description = "직관/집관 등록과 본인 기록 관리")
@ApiResponse(responseCode = "400", description = "요청 검증 오류",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
@ApiResponse(responseCode = "401", description = "인증 필요",
        content = @Content)
@ApiResponse(responseCode = "404", description = "경기 또는 본인 기록 없음",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
@ApiResponse(responseCode = "409", description = "기록 가능 시간 만료, 취소 경기, 기존 기록 충돌 또는 온보딩 미완료",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
public class UserGameLogController {
    private final GameService service;
    public UserGameLogController(GameService service) { this.service = service; }

    @PostMapping
    @Operation(summary = "직관·집관 등록", description = "KST 당일 00:00부터 등록 가능하며 전날 경기도 당일 첫 경기 시작 전까지 등록 가능합니다(경기 없음: 18:30). 화면 리셋은 첫 경기 30분 전이지만 등록 가능 시간은 그대로입니다. 동일 내용 재요청과 삭제 기록 복원은 같은 ID를 반환합니다.")
    @ApiResponse(responseCode = "201", description = "신규 등록")
    @ApiResponse(responseCode = "200", description = "기존 기록 또는 복원")
    public ResponseEntity<UserGameLogResponse> create(JwtAuthenticationToken auth, @Valid @RequestBody CreateUserGameLogRequest request) {
        var result = service.create(auth.getName(), request);
        return result.created() ? ResponseEntity.created(URI.create("/api/user-game-logs/" + result.response().id())).body(result.response())
                : ResponseEntity.ok(result.response());
    }

    @GetMapping("/{id}")
    @Operation(summary = "내 관람 기록 조회")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    public UserGameLogResponse get(JwtAuthenticationToken auth, @PathVariable @Positive long id) { return service.get(auth.getName(), id); }

    @PatchMapping("/{id}")
    @Operation(summary = "내 관람 방식 정정", description = "직관과 집관을 정정합니다. 이후 승률 조회에 즉시 반영됩니다.")
    @ApiResponse(responseCode = "200", description = "정정 성공")
    public UserGameLogResponse update(JwtAuthenticationToken auth, @PathVariable @Positive long id,
                                     @Valid @RequestBody UpdateUserGameLogRequest request) {
        return service.update(auth.getName(), id, request.viewingType());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "내 관람 기록 삭제", description = "소프트 삭제합니다. 반복 삭제는 204이며 다른 사용자의 기록은 404입니다.")
    @ApiResponse(responseCode = "204", description = "삭제 완료",
        content = @Content)
    public ResponseEntity<Void> delete(JwtAuthenticationToken auth, @PathVariable @Positive long id) {
        service.delete(auth.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
