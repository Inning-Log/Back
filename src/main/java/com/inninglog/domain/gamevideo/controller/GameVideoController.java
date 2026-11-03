package com.inninglog.domain.gamevideo.controller;

import com.inninglog.domain.game.controller.GameExceptionHandler;
import com.inninglog.domain.gamevideo.dto.GameVideoFriendsResponse;
import com.inninglog.domain.gamevideo.dto.GameVideoRequest;
import com.inninglog.domain.gamevideo.dto.GameVideoResponse;
import com.inninglog.domain.gamevideo.service.GameVideoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/games/{gameId}/video")
@Validated
@Tag(name = "경기 영상 저장", description = "한 경기의 이닝 영상을 9:16 영상 하나로 합칩니다. 친구 영상도 함께 넣을 수 있습니다.")
@ApiResponse(responseCode = "401", description = "인증 필요", content = @Content)
@ApiResponse(responseCode = "404", description = "경기·직관 기록·영상 없음",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
public class GameVideoController {
    private final GameVideoService service;

    public GameVideoController(GameVideoService service) { this.service = service; }

    @GetMapping("/friends")
    @Operation(summary = "함께 넣을 수 있는 친구", description = "이 경기를 기록했고 완료된 영상이 1개 이상 있는 수락한 친구입니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    public GameVideoFriendsResponse friends(JwtAuthenticationToken auth, @PathVariable @Positive long gameId) {
        return service.friends(auth.getName(), gameId);
    }

    @PostMapping
    @Operation(summary = "영상 저장 요청", description = "이닝별 구간을 만든 뒤 하나로 합칩니다. 완료되면 GENERATED_VIDEO_READY 알림이 가고, 조회 API에서 downloadUrl을 받습니다. 이미 있던 완료 영상은 새 영상으로 바뀝니다.")
    @ApiResponse(responseCode = "202", description = "요청 접수")
    @ApiResponse(responseCode = "400", description = "INVALID_FRIEND 또는 TOO_MANY_FRIENDS",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "GAME_VIDEO_IN_PROGRESS 또는 NO_CLIPS",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "GAME_VIDEO_DISABLED: 서버에 영상 처리가 설정되지 않음",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
    public ResponseEntity<GameVideoResponse> request(JwtAuthenticationToken auth, @PathVariable @Positive long gameId,
                                                     @RequestBody(required = false) GameVideoRequest request) {
        var response = service.request(auth.getName(), gameId, request == null ? null : request.friendIds());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping
    @Operation(summary = "내 경기 영상 상태", description = "RENDERING → MERGING → READY 또는 FAILED. READY면 downloadUrl이 있습니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    public GameVideoResponse get(JwtAuthenticationToken auth, @PathVariable @Positive long gameId) {
        return service.get(auth.getName(), gameId);
    }

    @DeleteMapping
    @Operation(summary = "내 경기 영상 삭제", description = "반복 삭제도 204입니다. 원본 이닝 영상은 남습니다.")
    @ApiResponse(responseCode = "204", description = "삭제 완료", content = @Content)
    public ResponseEntity<Void> delete(JwtAuthenticationToken auth, @PathVariable @Positive long gameId) {
        service.delete(auth.getName(), gameId);
        return ResponseEntity.noContent().build();
    }
}
