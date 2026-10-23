package com.inninglog.domain.comment.controller;

import com.inninglog.domain.comment.dto.*;
import com.inninglog.domain.comment.service.VideoCommentService;
import io.swagger.v3.oas.annotations.Operation;
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
public class VideoCommentController {
    private final VideoCommentService service;
    public VideoCommentController(VideoCommentService service) { this.service = service; }

    @GetMapping("/videos/{videoId}/comments")
    @Operation(summary = "영상 댓글 목록", description = "댓글과 답글을 오래된 순으로 함께 반환합니다. parentCommentId가 null이면 원댓글입니다. totalCount는 답글을 포함합니다. size 기본 20, 최대 100. nextCursor는 같은 영상에서만 사용합니다.")
    public CommentPageResponse list(JwtAuthenticationToken auth, @PathVariable @Positive long videoId,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(auth.getName(), videoId, cursor, size);
    }

    @PostMapping("/videos/{videoId}/comments")
    @Operation(summary = "영상 댓글 작성", description = "parentCommentId 생략 또는 null이면 원댓글, 지정하면 같은 영상의 원댓글에 답글을 작성합니다. 답글의 답글은 허용하지 않습니다. clientCommentId는 요청마다 새 UUID. 같은 내용과 부모 재전송은 200, 최초 생성은 201입니다.")
    public ResponseEntity<CommentResponse> create(JwtAuthenticationToken auth, @PathVariable @Positive long videoId,
                                                 @Valid @RequestBody CommentCreateRequest request) {
        var result = service.create(auth.getName(), videoId, request);
        return result.created() ? ResponseEntity.created(URI.create("/api/video-comments/" + result.comment().id())).body(result.comment())
                : ResponseEntity.ok(result.comment());
    }

    @GetMapping("/video-comments/{commentId}")
    @Operation(summary = "댓글 단건 조회", description = "알림의 commentId로 조회합니다. 삭제되었거나 영상 접근 권한이 사라지면 404입니다.")
    public CommentResponse get(JwtAuthenticationToken auth, @PathVariable @Positive long commentId) {
        return service.get(auth.getName(), commentId);
    }

    @PatchMapping("/video-comments/{commentId}")
    @Operation(summary = "내 댓글 수정", description = "본문만 수정하며 새 댓글 알림은 발생하지 않습니다.")
    public CommentResponse update(JwtAuthenticationToken auth, @PathVariable @Positive long commentId,
                                  @Valid @RequestBody CommentUpdateRequest request) {
        return service.update(auth.getName(), commentId, request);
    }

    @DeleteMapping("/video-comments/{commentId}")
    @Operation(summary = "내 댓글 삭제", description = "작성자만 삭제합니다. 원댓글 삭제 시 답글도 함께 삭제합니다. 같은 댓글 반복 삭제는 204입니다. 현재 영상 접근 권한이 필요합니다.")
    public ResponseEntity<Void> delete(JwtAuthenticationToken auth, @PathVariable @Positive long commentId) {
        service.delete(auth.getName(), commentId);
        return ResponseEntity.noContent().build();
    }
}
