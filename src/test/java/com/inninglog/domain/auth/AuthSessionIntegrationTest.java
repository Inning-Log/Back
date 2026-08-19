package com.inninglog.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inninglog.domain.auth.service.AccountDeletionService;
import com.inninglog.domain.auth.service.AccountDeletedException;
import com.inninglog.domain.auth.service.AuthSessionService;
import com.inninglog.domain.auth.service.GoogleIdentityTokenVerifier;
import com.inninglog.domain.auth.service.GoogleUserInfo;
import com.inninglog.domain.auth.service.InvalidRefreshTokenException;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.repository.UserRepository;
import com.inninglog.domain.mypage.service.MyPageService;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSessionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthSessionService authSessionService;

    @Autowired
    private AccountDeletionService accountDeletionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MyPageService myPageService;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void refreshRotatesBothTokensAndRejectsEveryPreviousToken() throws Exception {
        LoginTokens original = login("rotate");

        MvcResult refreshedResult = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refreshBody(original.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        LoginTokens refreshed = tokensFromRefresh(refreshedResult);

        assertThat(refreshed.accessToken()).isNotEqualTo(original.accessToken());
        assertThat(refreshed.refreshToken()).isNotEqualTo(original.refreshToken());
        secureGet(original.accessToken()).andExpect(status().isUnauthorized());
        refresh(original.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        secureGet(refreshed.accessToken()).andExpect(status().isOk());
    }

    @Test
    void logoutImmediatelyInvalidatesCurrentAccessAndRefreshTokens() throws Exception {
        LoginTokens tokens = login("logout");

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", bearer(tokens.accessToken())))
                .andExpect(status().isNoContent());

        secureGet(tokens.accessToken()).andExpect(status().isUnauthorized());
        refresh(tokens.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAllImmediatelyInvalidatesEveryDeviceSession() throws Exception {
        LoginTokens first = login("logout-all");
        LoginTokens second = login("logout-all");

        mockMvc.perform(post("/api/auth/logout-all")
                        .header("Authorization", bearer(first.accessToken())))
                .andExpect(status().isNoContent());

        secureGet(first.accessToken()).andExpect(status().isUnauthorized());
        secureGet(second.accessToken()).andExpect(status().isUnauthorized());
        refresh(first.refreshToken()).andExpect(status().isUnauthorized());
        refresh(second.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    void accountDeletionAnonymizesPersonalDataAndBlocksTokensAndRelogin() throws Exception {
        LoginTokens tokens = login("delete");

        mockMvc.perform(delete("/api/users/me")
                        .header("Authorization", bearer(tokens.accessToken())))
                .andExpect(status().isNoContent());

        secureGet(tokens.accessToken()).andExpect(status().isUnauthorized());
        refresh(tokens.refreshToken()).andExpect(status().isUnauthorized());
        googleLogin("delete")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DELETED"));

        User deleted = userRepository.findById(tokens.userId()).orElseThrow();
        assertThat(deleted.getDeletedAt()).isNotNull();
        assertThat(deleted.getEmail()).isEqualTo("deleted-" + tokens.userId() + "@deleted.invalid");
        assertThat(deleted.getNickname()).isNull();
        assertThat(deleted.getProfileImageUrl()).isNull();
        assertThat(deleted.getFavoriteTeam()).isNull();
    }

    @Test
    void concurrentRefreshAllowsExactlyOneRotation() throws Exception {
        LoginTokens original = login("concurrent-refresh");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> refreshCall = () -> {
                start.await(5, TimeUnit.SECONDS);
                return mockMvc.perform(post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(refreshBody(original.refreshToken())))
                        .andReturn();
            };
            Future<MvcResult> first = executor.submit(refreshCall);
            Future<MvcResult> second = executor.submit(refreshCall);
            start.countDown();

            List<MvcResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            List<Integer> statuses = new ArrayList<>(results.stream()
                    .map(result -> result.getResponse().getStatus())
                    .toList());
            Collections.sort(statuses);
            assertThat(statuses).containsExactly(200, 401);

            MvcResult success = results.stream()
                    .filter(result -> result.getResponse().getStatus() == 200)
                    .findFirst()
                    .orElseThrow();
            secureGet(tokensFromRefresh(success).accessToken()).andExpect(status().isOk());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDeletionAndRefreshAlwaysEndsDeletedWithNoUsableSession() throws Exception {
        LoginTokens original = login("delete-refresh-race");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> {
                await(start);
                accountDeletionService.deleteCurrentUser(String.valueOf(original.userId()));
            });
            Future<AuthSessionService.SessionTokens> refreshFuture = executor.submit(() -> {
                await(start);
                try {
                    return authSessionService.refresh(original.refreshToken());
                } catch (InvalidRefreshTokenException exception) {
                    return null;
                }
            });
            start.countDown();
            deletion.get(10, TimeUnit.SECONDS);
            AuthSessionService.SessionTokens possiblyIssued = refreshFuture.get(10, TimeUnit.SECONDS);

            User deleted = userRepository.findById(original.userId()).orElseThrow();
            assertThat(deleted.isDeleted()).isTrue();
            refresh(original.refreshToken()).andExpect(status().isUnauthorized());
            secureGet(original.accessToken()).andExpect(status().isUnauthorized());
            if (possiblyIssued != null) {
                secureGet(possiblyIssued.accessToken().accessToken()).andExpect(status().isUnauthorized());
                refresh(possiblyIssued.refreshToken()).andExpect(status().isUnauthorized());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentLogoutAndRefreshAlwaysEndsWithTheSessionRevoked() throws Exception {
        LoginTokens original = login("logout-refresh-race");
        Long sessionId = ((Number) jwtDecoder.decode(original.accessToken()).getClaim("sid")).longValue();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> logout = executor.submit(() -> {
                await(start);
                authSessionService.logout(String.valueOf(original.userId()), sessionId);
            });
            Future<AuthSessionService.SessionTokens> refreshFuture = executor.submit(() -> {
                await(start);
                try {
                    return authSessionService.refresh(original.refreshToken());
                } catch (InvalidRefreshTokenException exception) {
                    return null;
                }
            });
            start.countDown();
            logout.get(10, TimeUnit.SECONDS);
            AuthSessionService.SessionTokens possiblyIssued = refreshFuture.get(10, TimeUnit.SECONDS);

            secureGet(original.accessToken()).andExpect(status().isUnauthorized());
            refresh(original.refreshToken()).andExpect(status().isUnauthorized());
            if (possiblyIssued != null) {
                secureGet(possiblyIssued.accessToken().accessToken()).andExpect(status().isUnauthorized());
                refresh(possiblyIssued.refreshToken()).andExpect(status().isUnauthorized());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentDeletionIsIdempotentAtTheLockedDomainBoundary() throws Exception {
        LoginTokens original = login("concurrent-delete");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> deleteCall = () -> {
                start.await(5, TimeUnit.SECONDS);
                accountDeletionService.deleteCurrentUser(String.valueOf(original.userId()));
                return null;
            };
            Future<Void> first = executor.submit(deleteCall);
            Future<Void> second = executor.submit(deleteCall);
            start.countDown();

            assertThatCode(() -> first.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
            assertThatCode(() -> second.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
            assertThat(userRepository.findById(original.userId()).orElseThrow().isDeleted()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentProfileMutationCannotResurrectADeletedAccount() throws Exception {
        LoginTokens original = login("delete-profile-race");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> deletion = executor.submit(() -> {
                await(start);
                accountDeletionService.deleteCurrentUser(String.valueOf(original.userId()));
            });
            Future<?> profileUpdate = executor.submit(() -> {
                await(start);
                try {
                    myPageService.updateProfile(
                            String.valueOf(original.userId()),
                            "cannot.resurrect",
                            "Cannot Resurrect");
                } catch (AccountDeletedException ignored) {
                    // Deletion may acquire the user lock first; the invariant is verified below.
                }
            });
            start.countDown();
            deletion.get(10, TimeUnit.SECONDS);
            profileUpdate.get(10, TimeUnit.SECONDS);

            User deleted = userRepository.findById(original.userId()).orElseThrow();
            assertThat(deleted.isDeleted()).isTrue();
            assertThat(deleted.getEmail()).isEqualTo("deleted-" + original.userId() + "@deleted.invalid");
            assertThat(deleted.getNickname()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    private LoginTokens login(String suffix) throws Exception {
        MvcResult result = googleLogin(suffix)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return new LoginTokens(
                ((Number) JsonPath.read(body, "$.user.id")).longValue(),
                JsonPath.read(body, "$.accessToken"),
                JsonPath.read(body, "$.refreshToken"));
    }

    private org.springframework.test.web.servlet.ResultActions googleLogin(String suffix) throws Exception {
        return mockMvc.perform(post("/api/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"credential":"valid-session-%s"}
                        """.formatted(suffix)));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(refreshToken)));
    }

    private org.springframework.test.web.servlet.ResultActions secureGet(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/auth/me")
                .header("Authorization", bearer(accessToken)));
    }

    private static String refreshBody(String refreshToken) {
        return "{\"refreshToken\":\"" + refreshToken + "\"}";
    }

    private static String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private static LoginTokens tokensFromRefresh(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return new LoginTokens(
                -1L,
                JsonPath.read(body, "$.accessToken"),
                JsonPath.read(body, "$.refreshToken"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record LoginTokens(Long userId, String accessToken, String refreshToken) {
    }

    @TestConfiguration
    static class TestGoogleVerifierConfig {

        @Bean
        @Primary
        GoogleIdentityTokenVerifier testGoogleIdentityTokenVerifier() {
            return credential -> {
                if (!credential.startsWith("valid-session-")) {
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
