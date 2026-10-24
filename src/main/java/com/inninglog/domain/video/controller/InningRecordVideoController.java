package com.inninglog.domain.video.controller;

import com.inninglog.domain.video.dto.VideoResponse;
import com.inninglog.domain.video.dto.VideoUploadRequest;
import com.inninglog.domain.video.dto.VideoUploadResponse;
import com.inninglog.domain.video.service.VideoService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.game.controller.GameExceptionHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/inning-records/{recordId}/video")
@Validated
@Tag(name = "이닝 기록 영상", description = "기록 1개당 영상 1개. 파일은 Presigned URL로 S3에 직접 올리고 내려받습니다.")
@ApiResponse(responseCode = "400", description = "요청 형식 또는 지원하지 않는 영상 형식",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
@ApiResponse(responseCode = "401", description = "인증 필요",
        content = @Content)
@ApiResponse(responseCode = "404", description = "기록·영상 없음 또는 접근 권한 없음",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
@ApiResponse(responseCode = "503", description = "영상 저장소 미설정",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
public class InningRecordVideoController {
    private final VideoService service;
    public InningRecordVideoController(VideoService service) { this.service = service; }

    @PostMapping("/upload-url")
    @Operation(summary = "영상 업로드 URL 발급", description = "응답의 uploadUrl로 method(PUT)와 headers를 그대로 사용해 파일을 올린 뒤 complete를 호출합니다. 완료되지 않은 이전 업로드는 새 요청으로 대체됩니다.")
    @ApiResponse(responseCode = "200", description = "발급 성공")
    @ApiResponse(responseCode = "409", description = "이미 완료된 영상이 있음",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
    @ApiResponse(responseCode = "413", description = "최대 크기 초과",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
    public VideoUploadResponse uploadUrl(JwtAuthenticationToken auth, @PathVariable @Positive long recordId,
                                         @Valid @RequestBody VideoUploadRequest request) {
        return service.requestUpload(auth.getName(), recordId, request);
    }

    @PostMapping("/complete")
    @Operation(summary = "영상 업로드 완료 확인", description = "S3 객체의 크기와 Content-Type 메타데이터를 확인하고 READY로 바꿉니다. 영상 디코딩·합성 검증은 수행하지 않습니다. 완료 응답을 놓친 경우 이 API를 재호출하면 READY를 반환합니다. downloadUrl은 재발급될 수 있습니다.")
    @ApiResponse(responseCode = "200", description = "READY")
    @ApiResponse(responseCode = "409", description = "VIDEO_NOT_UPLOADED: S3 PUT 후 재시도. VIDEO_UPLOAD_EXPIRED 또는 VIDEO_UPLOAD_MISMATCH: 새 URL 발급 후 다시 업로드. 온보딩 미완료도 409",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = GameExceptionHandler.GameErrorResponse.class)))
    public VideoResponse complete(JwtAuthenticationToken auth, @PathVariable @Positive long recordId) {
        return service.complete(auth.getName(), recordId);
    }

    @GetMapping
    @Operation(summary = "영상 조회", description = "본인 또는 현재 수락한 친구가 조회합니다. READY면 expiresAt까지 유효한 downloadUrl을 반환하며 만료 시 이 API로 재발급합니다. 본인의 PENDING 영상은 URL·만료 시각·완료 시각이 null입니다. 친구에게 PENDING 영상은 404입니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    public VideoResponse get(JwtAuthenticationToken auth, @PathVariable @Positive long recordId) {
        return service.get(auth.getName(), recordId);
    }

    @DeleteMapping
    @Operation(summary = "내 영상 삭제", description = "작성자만 삭제할 수 있고 반복 삭제도 204입니다. 이닝 기록은 남습니다.")
    @ApiResponse(responseCode = "204", description = "삭제 완료",
        content = @Content)
    public ResponseEntity<Void> delete(JwtAuthenticationToken auth, @PathVariable @Positive long recordId) {
        service.delete(auth.getName(), recordId);
        return ResponseEntity.noContent().build();
    }
}
