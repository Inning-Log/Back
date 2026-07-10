package com.inninglog.domain.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inninglog.domain.auth.service.GoogleIdentityTokenVerifier;
import com.inninglog.domain.auth.service.GoogleUserInfo;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoogleLoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void googleLoginRegistersUserAndIssuesServiceJwt() throws Exception {
        String responseBody = mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "credential": "valid-google-token-register"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.isNewUser").value(true))
                .andExpect(jsonPath("$.user.email").value("valid-google-token-register@gmail.com"))
                .andExpect(jsonPath("$.user.nickname").value("Tester"))
                .andExpect(jsonPath("$.user.onboardingCompleted").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String accessToken = JsonPath.read(responseBody, "$.accessToken");
        Integer userId = JsonPath.read(responseBody, "$.user.id");

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value(String.valueOf(userId)))
                .andExpect(jsonPath("$.user.id").value(userId))
                .andExpect(jsonPath("$.user.email").value("valid-google-token-register@gmail.com"));
    }

    @Test
    void googleLoginReturnsExistingUserOnSecondLogin() throws Exception {
        String firstResponseBody = mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "credential": "valid-google-token-existing"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Integer userId = JsonPath.read(firstResponseBody, "$.user.id");

        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "credential": "valid-google-token-existing"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewUser").value(false))
                .andExpect(jsonPath("$.user.id").value(userId));
    }

    @Test
    void googleLoginRejectsInvalidCredential() throws Exception {
        mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "credential": "invalid-google-token"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_GOOGLE_TOKEN"));
    }

    @TestConfiguration
    static class TestGoogleVerifierConfig {

        @Bean
        @Primary
        GoogleIdentityTokenVerifier testGoogleIdentityTokenVerifier() {
            return credential -> {
                if (!credential.startsWith("valid-google-token-")) {
                    throw new BadJwtException("Invalid Google ID token.");
                }
                return new GoogleUserInfo(
                        "google-sub-" + credential,
                        credential + "@gmail.com",
                        "Tester",
                        "https://example.com/profile.png");
            };
        }
    }
}
