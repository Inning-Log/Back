package com.inninglog.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void authOnboardingAndMyPageExposeKoreanSuccessAndErrorDocumentation() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.summary")
                        .value("Google ID 토큰 로그인"))
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.responses['403']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/google'].post.responses['503']").exists())
                .andExpect(jsonPath("$.components.schemas.LoginResponse.properties.refreshToken").exists())
                .andExpect(jsonPath("$.components.schemas.LoginResponse.properties.expiresAt").exists())
                .andExpect(jsonPath("$.components.schemas.LoginResponse.properties.refreshTokenExpiresAt").exists())
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.summary")
                        .value("서비스 토큰 갱신"))
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.responses['400']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.responses['401']").exists())
                .andExpect(jsonPath("$.components.schemas.TokenPairResponse.properties.accessTokenExpiresAt")
                        .exists())
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post.summary")
                        .value("현재 세션 로그아웃"))
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/logout-all'].post.summary")
                        .value("모든 세션 로그아웃"))
                .andExpect(jsonPath("$.paths['/api/auth/logout-all'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/users/me'].delete.summary")
                        .value("회원 탈퇴"))
                .andExpect(jsonPath("$.paths['/api/users/me'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/users/me'].delete.responses['401']").exists())
                .andExpect(jsonPath("$.security[0].bearerAuth").exists())
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
                .andExpect(jsonPath("$.paths['/api/mypage/profile-image'].put.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/teams'].get.summary")
                        .value("활성 KBO 구단 목록 조회"))
                .andExpect(jsonPath("$.paths['/api/health'].get.summary")
                        .value("서비스 상태 확인"));
    }

    @Test
    void everyExposedOperationHasSummaryTagAndSuccessResponseAndPublicSecurityOverrides() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode document = objectMapper.readTree(body);
        Set<String> methods = Set.of("get", "post", "put", "patch", "delete");

        document.path("paths").properties().forEach(path -> path.getValue().properties().stream()
                .filter(operation -> methods.contains(operation.getKey()))
                .forEach(operation -> {
                    JsonNode definition = operation.getValue();
                    String operationName = operation.getKey().toUpperCase() + " " + path.getKey();
                    assertThat(definition.path("summary").asText())
                            .as(operationName + " summary")
                            .isNotBlank();
                    assertThat(definition.path("tags").isArray())
                            .as(operationName + " tags")
                            .isTrue();
                    assertThat(definition.path("tags").size())
                            .as(operationName + " tags")
                            .isPositive();
                    assertThat(definition.path("responses").propertyStream()
                            .anyMatch(response -> response.getKey().startsWith("2")))
                            .as(operationName + " success response")
                            .isTrue();
                }));

        for (String publicPath : Set.of(
                "/api/health",
                "/api/auth/dev-token",
                "/api/auth/google",
                "/api/auth/refresh",
                "/api/teams")) {
            document.path("paths").path(publicPath).properties().stream()
                    .filter(operation -> methods.contains(operation.getKey()))
                    .forEach(operation -> assertThat(operation.getValue().path("security").isEmpty())
                            .as(operation.getKey().toUpperCase() + " " + publicPath + " public security")
                            .isTrue());
        }
    }
}
