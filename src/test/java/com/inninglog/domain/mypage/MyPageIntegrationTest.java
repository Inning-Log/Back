package com.inninglog.domain.mypage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inninglog.domain.oauth.google.GoogleIdentityTokenVerifier;
import com.inninglog.domain.oauth.google.GoogleUserInfo;
import com.inninglog.domain.oauth.exception.InvalidGoogleTokenException;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyPageIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void myPageReturnsProfileAndFavoriteTeamDetails() throws Exception {
        String accessToken = completedUser("mypage-read", "mypage.read", 8L);

        mockMvc.perform(get("/api/mypage")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("Test Fan"))
                .andExpect(jsonPath("$.username").value("mypage.read"))
                .andExpect(jsonPath("$.email").value("valid-mypage-read@gmail.com"))
                .andExpect(jsonPath("$.favoriteTeam.id").value(8))
                .andExpect(jsonPath("$.favoriteTeam.name").value("KIA 타이거즈"));
    }

    @Test
    void profileUpdateNormalizesUsernameAndRejectsDuplicates() throws Exception {
        String firstToken = completedUser("mypage-profile-first", "mypage.first", 1L);
        String secondToken = completedUser("mypage-profile-second", "mypage.second", 2L);

        mockMvc.perform(get("/api/mypage/username-availability")
                        .header("Authorization", "Bearer " + secondToken)
                        .queryParam("username", "mypage.first"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));

        mockMvc.perform(get("/api/mypage/username-availability")
                        .header("Authorization", "Bearer " + firstToken)
                        .queryParam("username", "mypage.first"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));

        mockMvc.perform(patch("/api/mypage/profile")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "  Changed.User  ",
                                  "nickname": "  Changed Fan  "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("changed.user"))
                .andExpect(jsonPath("$.nickname").value("Changed Fan"));

        mockMvc.perform(patch("/api/mypage/profile")
                        .header("Authorization", "Bearer " + secondToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "changed.user",
                                  "nickname": "Another Fan"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"));
    }

    @Test
    void favoriteTeamCanBeChangedAfterOnboarding() throws Exception {
        String accessToken = completedUser("mypage-team", "mypage.team", 8L);

        mockMvc.perform(put("/api/mypage/favorite-team")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "favoriteTeamId": 9
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favoriteTeam.id").value(9))
                .andExpect(jsonPath("$.favoriteTeam.name").value("SSG 랜더스"));
    }

    @Test
    void customProfileImageIsPreservedOnNextGoogleLogin() throws Exception {
        String credentialSuffix = "mypage-image";
        String accessToken = completedUser(credentialSuffix, "mypage.image", 5L);

        mockMvc.perform(put("/api/mypage/profile-image")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "profileImageUrl": "https://cdn.example.com/custom-profile.png"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl")
                        .value("https://cdn.example.com/custom-profile.png"));

        String nextAccessToken = login(credentialSuffix);
        mockMvc.perform(get("/api/mypage")
                        .header("Authorization", "Bearer " + nextAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl")
                        .value("https://cdn.example.com/custom-profile.png"));
    }

    @Test
    void myPageRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/mypage"))
                .andExpect(status().isUnauthorized());
    }

    private String completedUser(String credentialSuffix, String username, Long favoriteTeamId) throws Exception {
        String accessToken = login(credentialSuffix);

        mockMvc.perform(put("/api/onboarding/username")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s"
                                }
                                """.formatted(username)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/onboarding/nickname")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nickname": "Test Fan"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/onboarding/favorite-team")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "favoriteTeamId": %d
                                }
                                """.formatted(favoriteTeamId)))
                .andExpect(status().isOk());

        return accessToken;
    }

    private String login(String credentialSuffix) throws Exception {
        String responseBody = mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "credential": "valid-%s"
                                }
                                """.formatted(credentialSuffix)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(responseBody, "$.accessToken");
    }

    @TestConfiguration
    static class TestGoogleVerifierConfig {

        @Bean
        @Primary
        GoogleIdentityTokenVerifier testGoogleIdentityTokenVerifier() {
            return credential -> {
                if (!credential.startsWith("valid-")) {
                    throw new InvalidGoogleTokenException("Invalid Google ID token.");
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
