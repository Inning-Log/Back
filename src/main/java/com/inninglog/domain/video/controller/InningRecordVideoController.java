package com.inninglog.domain.video.controller;

import com.inninglog.domain.video.dto.VideoResponse;
import com.inninglog.domain.video.dto.VideoUploadRequest;
import com.inninglog.domain.video.dto.VideoUploadResponse;
import com.inninglog.domain.video.service.VideoService;
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
@ApiResponse(responseCode = "400", description = "요청 형식 또는 지원하지 않는 영상 형식")
@ApiResponse(responseCode = "401", description = "인증 필요")
@ApiResponse(responseCode = "404", description = "기록·영상 없음 또는 접근 권한 없음")
@ApiResponse(responseCode = "503", description = "영상 저장소 미설정")
public class InningRecordVideoController {
    private final VideoService service;
    public InningRecordVideoController(VideoService service) { this.service = service; }

    @PostMapping("/upload-url")
    @Operation(summary = "영상 업로드 URL 발급", description = "응답의 uploadUrl로 method(PUT)와 headers를 그대로 사용해 파일을 올린 뒤 complete를 호출합니다. 완료되지 않은 이전 업로드는 새 요청으로 대체됩니다.")
    @ApiResponse(responseCode = "200", description = "발급 성공")
    @ApiResponse(responseCode = "409", description = "이미 완료된 영상이 있음")
    @ApiResponse(responseCode = "413", description = "최대 크기 초과")
    public VideoUploadResponse uploadUrl(JwtAuthenticationToken auth, @PathVariable @Positive long recordId,
                                         @Valid @RequestBody VideoUploadRequest request) {
        return service.requestUpload(auth.getName(), recordId, request);
    }

    @PostMapping("/complete")
    @Operation(summary = "영상 업로드 완료 확인", description = "S3에 올라간 파일의 크기·형식을 확인하고 READY로 바꿉니다. 반복 호출해도 같은 결과입니다.")
    @ApiResponse(responseCode = "200", description = "READY")
    @ApiResponse(responseCode = "409", description = "파일 없음 또는 크기·형식 불일치")
    public VideoResponse complete(JwtAuthenticationToken auth, @PathVariable @Positive long recordId) {
        return service.complete(auth.getName(), recordId);
    }

    @GetMapping
    @Operation(summary = "영상 조회", description = "본인 또는 현재 수락한 친구가 조회합니다. READY면 짧게 유효한 downloadUrl을 반환합니다. 친구에게는 업로드 중인 영상이 보이지 않습니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    public VideoResponse get(JwtAuthenticationToken auth, @PathVariable @Positive long recordId) {
        return service.get(auth.getName(), recordId);
    }

    @DeleteMapping
    @Operation(summary = "내 영상 삭제", description = "작성자만 삭제할 수 있고 반복 삭제도 204입니다. 이닝 기록은 남습니다.")
    @ApiResponse(responseCode = "204", description = "삭제 완료")
    public ResponseEntity<Void> delete(JwtAuthenticationToken auth, @PathVariable @Positive long recordId) {
        service.delete(auth.getName(), recordId);
        return ResponseEntity.noContent().build();
    }
}
