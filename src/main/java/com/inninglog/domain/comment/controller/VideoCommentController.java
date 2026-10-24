package com.inninglog.domain.comment.controller;

import com.inninglog.domain.comment.dto.*;
import com.inninglog.domain.comment.service.VideoCommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Validated
@Tag(name = "개별 영상 댓글", description = "업로드 완료된 각 영상의 댓글. 현재 친구와 영상 소유자만 접근합니다.")
@ApiResponse(responseCode = "401", description = "인증 필요", content = @Content)
@ApiResponse(responseCode = "404", description = "댓글·영상 없음 또는 현재 접근 권한 없음",
        content = @Content(mediaType = "application/json", schema = @Schema(implementation = VideoCommentExceptionHandler.ErrorResponse.class)))
public class VideoCommentController {
    private final VideoCommentService service;
    public VideoCommentController(VideoCommentService service) { this.service = service; }

    @GetMapping("/videos/{videoId}/comments")
    @Operation(summary = "영상 댓글 목록", description = "댓글과 답글을 오래된 순으로 함께 반환합니다. parentCommentId가 null이면 원댓글입니다. totalCount는 답글을 포함합니다. size 기본 20, 최대 100. nextCursor는 같은 영상에서만 사용합니다.")
    @ApiResponse(responseCode = "200", description = "원댓글·답글이 함께 있는 평면 목록",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommentPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 영상 ID·size·cursor 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = VideoCommentExceptionHandler.ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "온보딩 미완료",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = VideoCommentExceptionHandler.ErrorResponse.class)))
    public CommentPageResponse list(JwtAuthenticationToken auth, @PathVariable @Positive long videoId,
                                    @Parameter(description = "직전 응답의 nextCursor. 첫 페이지는 생략") @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(auth.getName(), videoId, cursor, size);
    }

    @PostMapping("/videos/{videoId}/comments")
    @Operation(summary = "영상 댓글 작성", description = "parentCommentId 생략 또는 null이면 원댓글, 지정하면 같은 영상의 원댓글에 1계층 답글을 작성합니다. 답글의 답글은 허용하지 않습니다. 새 댓글마다 UUID를 생성하고 재시도에는 같은 UUID·본문·부모를 유지합니다. 원댓글은 영상 주인, 답글은 부모 댓글 작성자에게 알림을 보냅니다. 본인에게는 알리지 않습니다.")
    @ApiResponse(responseCode = "201", description = "최초 생성. Location에 댓글 단건 조회 경로 반환",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommentResponse.class)))
    @ApiResponse(responseCode = "200", description = "같은 clientCommentId·본문·부모의 재전송. 알림 중복 생성 없음",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommentResponse.class)))
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST 또는 COMMENT_REPLY_DEPTH_EXCEEDED(답글에 다시 답글)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = VideoCommentExceptionHandler.ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "동일 clientCommentId를 다른 내용·부모에 사용하거나 온보딩 미완료",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = VideoCommentExceptionHandler.ErrorResponse.class)))
    public ResponseEntity<CommentResponse> create(JwtAuthenticationToken auth, @PathVariable @Positive long videoId,
                                                 @Valid @RequestBody CommentCreateRequest request) {
        var result = service.create(auth.getName(), videoId, request);
        return result.created() ? ResponseEntity.created(URI.create("/api/video-comments/" + result.comment().id())).body(result.comment())
                : ResponseEntity.ok(result.comment());
    }

    @GetMapping("/video-comments/{commentId}")
    @Operation(summary = "댓글 단건 조회", description = "알림의 commentId로 조회합니다. 삭제되었거나 영상 접근 권한이 사라지면 404입니다.")
    @ApiResponse(responseCode = "200", description = "댓글 또는 답글 조회",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommentResponse.class)))
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 댓글 ID 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = VideoCommentExceptionHandler.ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "온보딩 미완료",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = VideoCommentExceptionHandler.ErrorResponse.class)))
    public CommentResponse get(JwtAuthenticationToken auth, @PathVariable @Positive long commentId) {
        return service.get(auth.getName(), commentId);
    }

    @PatchMapping("/video-comments/{commentId}")
    @Operation(summary = "내 댓글 수정", description = "본문만 수정하며 새 댓글 알림은 발생하지 않습니다.")
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 댓글 ID·본문 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = VideoCommentExceptionHandler.ErrorResponse.class)))
    @ApiResponse(responseCode = "200", description = "수정된 댓글 또는 답글",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = CommentResponse.class)))
    @ApiResponse(responseCode = "409", description = "온보딩 미완료",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = VideoCommentExceptionHandler.ErrorResponse.class)))
    public CommentResponse update(JwtAuthenticationToken auth, @PathVariable @Positive long commentId,
                                  @Valid @RequestBody CommentUpdateRequest request) {
        return service.update(auth.getName(), commentId, request);
    }

    @DeleteMapping("/video-comments/{commentId}")
    @Operation(summary = "내 댓글 삭제", description = "작성자만 삭제합니다. 원댓글 삭제 시 답글도 함께 삭제합니다. 같은 댓글 반복 삭제는 204입니다. 현재 영상 접근 권한이 필요합니다.")
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST: 댓글 ID 오류",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = VideoCommentExceptionHandler.ErrorResponse.class)))
    @ApiResponse(responseCode = "204", description = "삭제 완료. 응답 본문 없음", content = @Content)
    @ApiResponse(responseCode = "409", description = "온보딩 미완료",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = VideoCommentExceptionHandler.ErrorResponse.class)))
    public ResponseEntity<Void> delete(JwtAuthenticationToken auth, @PathVariable @Positive long commentId) {
        service.delete(auth.getName(), commentId);
        return ResponseEntity.noContent().build();
    }
}
