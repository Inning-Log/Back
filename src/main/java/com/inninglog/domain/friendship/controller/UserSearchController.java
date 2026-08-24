package com.inninglog.domain.friendship.controller;

import com.inninglog.domain.friendship.dto.UserSearchResponse;
import com.inninglog.domain.friendship.service.FriendshipService;
import com.inninglog.domain.user.service.UsernamePolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@Validated
@Tag(name = "친구", description = "친구 검색 및 관계 관리 API")
public class UserSearchController {

    private final FriendshipService friendshipService;

    public UserSearchController(FriendshipService friendshipService) {
        this.friendshipService = friendshipService;
    }

    @Operation(
            summary = "친구 추가용 사용자 검색",
            description = "아이디 앞부분으로 최대 20명을 검색합니다. 본인, 탈퇴 사용자, 온보딩 미완료 사용자는 제외하고 현재 관계 상태를 함께 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검색 성공",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = UserSearchResponse.class)))),
            @ApiResponse(responseCode = "400", description = "검색어 형식 또는 길이가 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "404", description = "JWT의 사용자 정보가 존재하지 않음",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "온보딩이 완료되지 않음",
                    content = @Content(schema = @Schema(
                            implementation = FriendshipExceptionHandler.FriendshipErrorResponse.class)))
    })
    @GetMapping("/search")
    public List<UserSearchResponse> searchUsers(
            JwtAuthenticationToken authentication,
            @Parameter(description = "@를 제외한 사용자 아이디 앞부분", example = "inning", required = true)
            @RequestParam
            @NotBlank
            @Size(max = UsernamePolicy.MAX_LENGTH)
            @Pattern(regexp = UsernamePolicy.PATTERN)
            String username
    ) {
        return friendshipService.searchUsers(authentication.getName(), username);
    }
}
