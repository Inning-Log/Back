package com.inninglog.domain.timeline.controller;

import com.inninglog.domain.timeline.dto.*;
import com.inninglog.domain.timeline.service.TimelineService;
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
@RequestMapping("/api/inning-records")
@Validated
@Tag(name = "이닝 기록", description = "타임라인 기록 저장·상세·문구 정정·삭제. 영상 업로드와 합성은 별도 기능입니다.")
@ApiResponse(responseCode = "400", description = "요청 형식 또는 촬영 시각 오류")
@ApiResponse(responseCode = "401", description = "인증 필요")
@ApiResponse(responseCode = "404", description = "기록 없음 또는 접근 권한 없음")
@ApiResponse(responseCode = "409", description = "관람 미등록, 기록 불가 경기, 중복 요청 충돌 또는 삭제된 기록")
public class InningRecordController {
    private final TimelineService service;
    public InningRecordController(TimelineService service) { this.service = service; }

    @PostMapping
    @Operation(summary = "이닝 기록 등록", description = "먼저 gameId로 직관·집관을 등록합니다. 같은 이닝의 여러 기록을 허용하며 clientRecordId(UUID)로 재시도를 구분합니다. 점수는 서버 관측에서만 가져옵니다.")
    @ApiResponse(responseCode = "201", description = "신규 기록")
    @ApiResponse(responseCode = "200", description = "동일 요청 재시도")
    public ResponseEntity<InningRecordResponse> create(JwtAuthenticationToken auth, @Valid @RequestBody CreateInningRecordRequest request) {
        var result = service.create(auth.getName(), request);
        return result.created()
                ? ResponseEntity.created(URI.create("/api/inning-records/" + result.response().id())).body(result.response())
                : ResponseEntity.ok(result.response());
    }

    @GetMapping("/{id}")
    @Operation(summary = "이닝 기록 상세", description = "본인 또는 현재 수락한 친구의 활성 기록만 조회할 수 있습니다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    public InningRecordResponse get(JwtAuthenticationToken auth, @PathVariable @Positive long id) {
        return service.get(auth.getName(), id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "내 기록 문구 정정", description = "text만 정정합니다. 이닝·촬영 시각·당시 점수는 바뀌지 않습니다.")
    @ApiResponse(responseCode = "200", description = "정정 성공")
    public InningRecordResponse update(JwtAuthenticationToken auth, @PathVariable @Positive long id,
                                       @Valid @RequestBody UpdateInningRecordRequest request) {
        return service.update(auth.getName(), id, request.text());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "내 이닝 기록 삭제", description = "반복 삭제도 204입니다. 이닝 기록을 삭제해도 관람 등록과 승률은 유지됩니다.")
    @ApiResponse(responseCode = "204", description = "삭제 완료")
    public ResponseEntity<Void> delete(JwtAuthenticationToken auth, @PathVariable @Positive long id) {
        service.delete(auth.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
