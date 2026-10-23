package com.inninglog.domain.mypage.controller;

import com.inninglog.domain.mypage.image.ProfileImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "마이페이지")
public class ProfileImageController {
    private final ProfileImageService service;
    public ProfileImageController(ProfileImageService service) { this.service = service; }

    @Operation(summary = "현재 프로필 이미지 표시", description =
            "img 태그에서 사용하는 공개 이미지 주소입니다. 현재 사용 중인 검증된 이미지만 짧은 S3 다운로드 URL로 이동합니다.")
    @GetMapping("/api/profile-images/{id}")
    @SecurityRequirements
    @ApiResponse(responseCode = "302", description = "검증된 현재 이미지의 S3 다운로드 URL로 이동")
    @ApiResponse(responseCode = "404", description = "이미지가 없거나 교체·초기화·탈퇴로 더 이상 공개되지 않음")
    public ResponseEntity<Void> image(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(service.download(id)))
                .cacheControl(CacheControl.noStore()).build();
    }
}

