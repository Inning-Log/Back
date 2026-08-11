package com.inninglog.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiDocumentationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void authOnboardingAndMyPageExposeKoreanSuccessAndErrorDocumentation() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.summary")
                        .value("Google ID 토큰 로그인"))
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.responses['503']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/dev-token'].post.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/profile'].put.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/me'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/onboarding'].get.summary")
                        .value("온보딩 진행 상태 조회"))
                .andExpect(jsonPath("$.paths['/api/onboarding'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/onboarding'].get.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/onboarding'].get.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/onboarding/username'].put.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/mypage'].get.summary")
                        .value("내 프로필 조회"))
                .andExpect(jsonPath("$.paths['/api/mypage/profile'].patch.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/mypage/profile'].patch.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/mypage/profile-image'].put.responses['404']").exists());
    }
}
