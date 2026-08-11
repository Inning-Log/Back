package com.inninglog.domain.mypage.controller;

import com.inninglog.domain.auth.service.UsernamePolicy;
import com.inninglog.domain.mypage.dto.FavoriteTeamUpdateRequest;
import com.inninglog.domain.mypage.dto.MyPageResponse;
import com.inninglog.domain.mypage.dto.ProfileImageUpdateRequest;
import com.inninglog.domain.mypage.dto.ProfileUpdateRequest;
import com.inninglog.domain.mypage.dto.UsernameAvailabilityResponse;
import com.inninglog.domain.mypage.service.MyPageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mypage")
@Validated
@Tag(name = "마이페이지", description = "내 프로필 조회 및 아이디·닉네임·응원팀·프로필 이미지 수정 API")
public class MyPageController {

    private final MyPageService myPageService;

    public MyPageController(MyPageService myPageService) {
        this.myPageService = myPageService;
    }

    @Operation(
            summary = "내 프로필 조회",
            description = "닉네임, 아이디, 이메일, 프로필 이미지와 현재 응원팀 정보를 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "프로필 조회 성공",
                    content = @Content(schema = @Schema(implementation = MyPageResponse.class))
            ),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = MyPageExceptionHandler.MyPageErrorResponse.class))
            )
    })
    @GetMapping
    public MyPageResponse getMyPage(JwtAuthenticationToken authentication) {
        return myPageService.getMyPage(authentication.getName());
    }

    @Operation(
            summary = "변경할 아이디 사용 가능 여부 확인",
            description = "입력값을 소문자로 정규화한 뒤 중복 여부를 확인합니다. 현재 사용자의 기존 아이디는 사용 가능으로 판단합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "중복 확인 성공. available 값으로 사용 가능 여부 판단",
                    content = @Content(schema = @Schema(implementation = UsernameAvailabilityResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "아이디 형식 또는 길이가 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = MyPageExceptionHandler.MyPageErrorResponse.class))
            )
    })
    @GetMapping("/username-availability")
    public UsernameAvailabilityResponse checkUsernameAvailability(
            JwtAuthenticationToken authentication,
            @Parameter(description = "@를 제외한 변경할 아이디", example = "inning.log", required = true)
            @RequestParam
            @NotBlank
            @Size(max = UsernamePolicy.MAX_LENGTH)
            @Pattern(regexp = UsernamePolicy.PATTERN)
            String username
    ) {
        return myPageService.checkUsernameAvailability(authentication.getName(), username);
    }

    @Operation(
            summary = "아이디와 닉네임 수정",
            description = "아이디를 소문자로 정규화하고 닉네임과 함께 저장합니다. 이메일은 수정되지 않습니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "프로필 수정 성공",
                    content = @Content(schema = @Schema(implementation = MyPageResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "아이디 또는 닉네임의 형식·길이가 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = MyPageExceptionHandler.MyPageErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "변경하려는 아이디가 이미 사용 중임",
                    content = @Content(schema = @Schema(implementation = MyPageExceptionHandler.MyPageErrorResponse.class))
            )
    })
    @PatchMapping("/profile")
    public MyPageResponse updateProfile(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody ProfileUpdateRequest request
    ) {
        return myPageService.updateProfile(
                authentication.getName(),
                request.username(),
                request.nickname());
    }

    @Operation(
            summary = "응원팀 변경",
            description = "현재 응원팀을 요청한 활성 KBO 팀으로 변경합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "응원팀 변경 성공",
                    content = @Content(schema = @Schema(implementation = MyPageResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "응원팀 ID가 없거나 양수가 아님", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "사용자 또는 활성 응원팀을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = MyPageExceptionHandler.MyPageErrorResponse.class))
            )
    })
    @PutMapping("/favorite-team")
    public MyPageResponse updateFavoriteTeam(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody FavoriteTeamUpdateRequest request
    ) {
        return myPageService.updateFavoriteTeam(authentication.getName(), request.favoriteTeamId());
    }

    @Operation(
            summary = "프로필 이미지 URL 변경 또는 초기화",
            description = "프로필 이미지 URL을 저장합니다. null 또는 빈 문자열을 보내면 사용자 지정 이미지를 초기화합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "프로필 이미지 변경 또는 초기화 성공",
                    content = @Content(schema = @Schema(implementation = MyPageResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "프로필 이미지 URL이 500자를 초과함", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = MyPageExceptionHandler.MyPageErrorResponse.class))
            )
    })
    @PutMapping("/profile-image")
    public MyPageResponse updateProfileImage(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody ProfileImageUpdateRequest request
    ) {
        return myPageService.updateProfileImage(authentication.getName(), request.profileImageUrl());
    }
}
