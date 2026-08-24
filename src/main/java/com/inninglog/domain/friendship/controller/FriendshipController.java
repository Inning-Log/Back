package com.inninglog.domain.friendship.controller;

import com.inninglog.domain.friendship.dto.FriendRequestCreateRequest;
import com.inninglog.domain.friendship.dto.FriendshipResponse;
import com.inninglog.domain.friendship.service.FriendshipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/friendships")
@Validated
@Tag(name = "친구", description = "친구 검색 및 관계 관리 API")
public class FriendshipController {

    private final FriendshipService friendshipService;

    public FriendshipController(FriendshipService friendshipService) {
        this.friendshipService = friendshipService;
    }

    @Operation(
            summary = "친구 신청",
            description = "검색 결과의 사용자 ID로 친구 신청을 보냅니다. 거절됐던 관계는 새 요청 주기로 재사용합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "친구 신청 생성 성공",
                    content = @Content(schema = @Schema(implementation = FriendshipResponse.class))),
            @ApiResponse(responseCode = "400", description = "요청 본문이 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "404", description = "상대 사용자 또는 현재 사용자가 존재하지 않음",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "자기 자신, 기존 친구, 중복 요청 또는 온보딩 미완료",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class)))
    })
    @PostMapping("/requests")
    public ResponseEntity<FriendshipResponse> sendRequest(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody FriendRequestCreateRequest request
    ) {
        FriendshipResponse response = friendshipService.sendRequest(
                authentication.getName(), request.receiverId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "친구 목록 조회",
            description = "현재 사용자의 수락된 친구 목록을 최근 수락 순으로 반환합니다. 공유 대상 선택에도 사용할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "친구 목록 조회 성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = FriendshipResponse.class)))),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "404", description = "JWT의 사용자 정보가 존재하지 않음",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "거절된 요청이거나 온보딩이 완료되지 않음",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class)))
    })
    @GetMapping
    public List<FriendshipResponse> getFriends(JwtAuthenticationToken authentication) {
        return friendshipService.getFriends(authentication.getName());
    }

    @Operation(
            summary = "받은 친구 신청 목록 조회",
            description = "현재 사용자가 수신한 PENDING 신청만 최신순으로 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "받은 신청 목록 조회 성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = FriendshipResponse.class)))),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "404", description = "JWT의 사용자 정보가 존재하지 않음",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "온보딩이 완료되지 않음",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class)))
    })
    @GetMapping("/requests")
    public List<FriendshipResponse> getReceivedRequests(JwtAuthenticationToken authentication) {
        return friendshipService.getReceivedRequests(authentication.getName());
    }

    @Operation(summary = "친구 신청 수락", description = "신청을 받은 사용자만 PENDING 요청을 수락할 수 있습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "친구 신청 수락 성공",
                    content = @Content(schema = @Schema(implementation = FriendshipResponse.class))),
            @ApiResponse(responseCode = "400", description = "관계 ID가 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "403", description = "현재 사용자가 신청 수신자가 아님",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "관계 또는 상대 사용자가 존재하지 않음",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "수락할 수 없는 관계 상태",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class)))
    })
    @PostMapping("/{id}/accept")
    public FriendshipResponse accept(
            JwtAuthenticationToken authentication,
            @Parameter(description = "친구 관계 ID", required = true) @PathVariable @Positive Long id
    ) {
        return friendshipService.accept(authentication.getName(), id);
    }

    @Operation(summary = "친구 신청 거절", description = "신청을 받은 사용자만 PENDING 요청을 거절할 수 있습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "친구 신청 거절 성공",
                    content = @Content(schema = @Schema(implementation = FriendshipResponse.class))),
            @ApiResponse(responseCode = "400", description = "관계 ID가 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "403", description = "현재 사용자가 신청 수신자가 아님",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "관계 또는 상대 사용자가 존재하지 않음",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "거절할 수 없는 관계 상태",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class)))
    })
    @PostMapping("/{id}/reject")
    public FriendshipResponse reject(
            JwtAuthenticationToken authentication,
            @Parameter(description = "친구 관계 ID", required = true) @PathVariable @Positive Long id
    ) {
        return friendshipService.reject(authentication.getName(), id);
    }

    @Operation(
            summary = "친구 관계 삭제 또는 보낸 신청 취소",
            description = "수락된 친구는 양쪽 모두 삭제할 수 있고, PENDING 신청은 요청자만 취소할 수 있습니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "친구 관계 삭제 또는 신청 취소 완료"),
            @ApiResponse(responseCode = "400", description = "관계 ID가 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "403", description = "받은 PENDING 신청을 삭제로 처리하려 함",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "관계가 없거나 현재 사용자의 관계가 아님",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "온보딩이 완료되지 않음",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            JwtAuthenticationToken authentication,
            @Parameter(description = "친구 관계 ID", required = true) @PathVariable @Positive Long id
    ) {
        friendshipService.delete(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
