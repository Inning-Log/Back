package com.inninglog.domain.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                .andExpect(jsonPath("$.user.username").doesNotExist())
                .andExpect(jsonPath("$.user.nickname").doesNotExist())
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

    @Test
    void profileSetupStoresUsernameAndNickname() throws Exception {
        String accessToken = login("profile-setup");

        mockMvc.perform(put("/api/auth/profile")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "  inning-user  ",
                                  "nickname": "  Inning Logger  "
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("inning-user"))
                .andExpect(jsonPath("$.nickname").value("Inning Logger"))
                .andExpect(jsonPath("$.onboardingCompleted").value(false));

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.username").value("inning-user"))
                .andExpect(jsonPath("$.user.nickname").value("Inning Logger"))
                .andExpect(jsonPath("$.user.onboardingCompleted").value(false));
    }

    @Test
    void initialFavoriteTeamSelectionCompletesOnboarding() throws Exception {
        String accessToken = login("favorite-team-selection");
        setupProfile(accessToken, "favorite-team-user", "Team Fan")
                .andExpect(status().isOk());

        selectFavoriteTeam(accessToken, 3L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favoriteTeamId").value(3))
                .andExpect(jsonPath("$.onboardingCompleted").value(true));

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.favoriteTeamId").value(3))
                .andExpect(jsonPath("$.user.onboardingCompleted").value(true));
    }

    @Test
    void initialFavoriteTeamSelectionRequiresProfileSetup() throws Exception {
        String accessToken = login("favorite-team-without-profile");

        selectFavoriteTeam(accessToken, 3L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_SETUP_REQUIRED"));
    }

    @Test
    void initialFavoriteTeamCannotBeSelectedTwice() throws Exception {
        String accessToken = login("favorite-team-twice");
        setupProfile(accessToken, "favorite-team-twice", "Team Fan")
                .andExpect(status().isOk());
        selectFavoriteTeam(accessToken, 3L)
                .andExpect(status().isOk());

        selectFavoriteTeam(accessToken, 4L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FAVORITE_TEAM_ALREADY_SELECTED"));
    }

    @Test
    void initialFavoriteTeamMustExistAndBeActive() throws Exception {
        String accessToken = login("unknown-favorite-team");
        setupProfile(accessToken, "unknown-favorite-team", "Team Fan")
                .andExpect(status().isOk());

        selectFavoriteTeam(accessToken, 9999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
    }

    @Test
    void profileSetupRejectsDuplicateUsernameButAllowsDuplicateNickname() throws Exception {
        String firstAccessToken = login("duplicate-username-first");
        String secondAccessToken = login("duplicate-username-second");

        setupProfile(firstAccessToken, "unique-username", "Same Nickname")
                .andExpect(status().isOk());

        setupProfile(secondAccessToken, "unique-username", "Different Nickname")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"));

        setupProfile(secondAccessToken, "another-username", "Same Nickname")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("another-username"))
                .andExpect(jsonPath("$.nickname").value("Same Nickname"));
    }

    @Test
    void usernameAvailabilityChecksDuplicatesAndAllowsCurrentUsersUsername() throws Exception {
        String firstAccessToken = login("availability-first");
        String secondAccessToken = login("availability-second");

        checkUsernameAvailability(firstAccessToken, "  available-username  ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("available-username"))
                .andExpect(jsonPath("$.available").value(true));

        setupProfile(firstAccessToken, "available-username", "First Nickname")
                .andExpect(status().isOk());

        checkUsernameAvailability(secondAccessToken, "available-username")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));

        checkUsernameAvailability(firstAccessToken, "available-username")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    void profileSetupRequiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/auth/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "inning-user",
                                  "nickname": "Inning Logger"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    private String login(String credentialSuffix) throws Exception {
        String responseBody = mockMvc.perform(post("/api/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "credential": "valid-google-token-%s"
                                }
                                """.formatted(credentialSuffix)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return JsonPath.read(responseBody, "$.accessToken");
    }

    private org.springframework.test.web.servlet.ResultActions setupProfile(
            String accessToken,
            String username,
            String nickname
    ) throws Exception {
        return mockMvc.perform(put("/api/auth/profile")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "username": "%s",
                          "nickname": "%s"
                        }
                        """.formatted(username, nickname)));
    }

    private org.springframework.test.web.servlet.ResultActions checkUsernameAvailability(
            String accessToken,
            String username
    ) throws Exception {
        return mockMvc.perform(get("/api/auth/profile/username-availability")
                .header("Authorization", "Bearer " + accessToken)
                .queryParam("username", username));
    }

    private org.springframework.test.web.servlet.ResultActions selectFavoriteTeam(
            String accessToken,
            Long favoriteTeamId
    ) throws Exception {
        return mockMvc.perform(post("/api/auth/profile/favorite-team")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "favoriteTeamId": %d
                        }
                        """.formatted(favoriteTeamId)));
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
