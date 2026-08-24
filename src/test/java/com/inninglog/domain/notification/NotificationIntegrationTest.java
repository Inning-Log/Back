package com.inninglog.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inninglog.domain.notification.entity.UserPushToken;
import com.inninglog.domain.notification.repository.UserPushTokenRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.repository.UserRepository;
import com.inninglog.domain.user.service.AccountDeletionService;
import com.inninglog.global.security.JwtTokenProvider;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPushTokenRepository pushTokenRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AccountDeletionService accountDeletionService;

    @BeforeEach
    void clearPushTokens() {
        pushTokenRepository.deleteAll();
    }

    @Test
    void pushTokenCanBeRegisteredRotatedAndDisabled() throws Exception {
        User user = userRepository.save(new User("push-owner@example.com", null));
        String accessToken = accessToken(user);

        register(accessToken, "ANDROID", "device-a", "token-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("ANDROID"))
                .andExpect(jsonPath("$.deviceId").value("device-a"))
                .andExpect(jsonPath("$.enabled").value(true));

        register(accessToken, "ANDROID", "device-a", "token-b")
                .andExpect(status().isOk());

        assertThat(pushTokenRepository.findByPushToken("token-a")).isEmpty();
        assertThat(pushTokenRepository.findByPushToken("token-b")).isPresent();
        assertThat(pushTokenRepository.count()).isEqualTo(1);

        mockMvc.perform(delete("/api/notifications/push-token")
                        .header("Authorization", "Bearer " + accessToken)
                        .queryParam("deviceId", "device-a"))
                .andExpect(status().isNoContent());

        assertThat(pushTokenRepository.findByPushToken("token-b"))
                .get()
                .extracting(UserPushToken::isEnabled)
                .isEqualTo(false);
    }

    @Test
    void samePushTokenMovesToTheMostRecentlyAuthenticatedUser() throws Exception {
        User firstUser = userRepository.save(new User("push-first@example.com", null));
        User secondUser = userRepository.save(new User("push-second@example.com", null));

        register(accessToken(firstUser), "IOS", "first-device", "shared-token")
                .andExpect(status().isOk());
        register(accessToken(secondUser), "IOS", "second-device", "shared-token")
                .andExpect(status().isOk());

        UserPushToken registration = pushTokenRepository.findByPushToken("shared-token").orElseThrow();
        assertThat(registration.getUserId()).isEqualTo(secondUser.getId());
        assertThat(registration.getDeviceId()).isEqualTo("second-device");
        assertThat(pushTokenRepository.count()).isEqualTo(1);
    }

    @Test
    void sameInstallationMovesToTheNewUserAndLateLogoutCannotDisableTheNewOwner() throws Exception {
        User firstUser = userRepository.save(new User("installation-first@example.com", null));
        User secondUser = userRepository.save(new User("installation-second@example.com", null));

        register(accessToken(firstUser), "ANDROID", "shared-installation", "first-token")
                .andExpect(status().isOk());
        register(accessToken(secondUser), "ANDROID", "shared-installation", "second-token")
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/notifications/push-token")
                        .header("Authorization", "Bearer " + accessToken(firstUser))
                        .queryParam("deviceId", "shared-installation"))
                .andExpect(status().isNoContent());

        UserPushToken registration = pushTokenRepository.findByDeviceId("shared-installation").orElseThrow();
        assertThat(registration.getUserId()).isEqualTo(secondUser.getId());
        assertThat(registration.getPushToken()).isEqualTo("second-token");
        assertThat(registration.isEnabled()).isTrue();
        assertThat(pushTokenRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationForTheSameInstallationDoesNotViolateUniqueConstraints() throws Exception {
        User firstUser = userRepository.save(new User("concurrent-first@example.com", null));
        User secondUser = userRepository.save(new User("concurrent-second@example.com", null));
        String firstAccessToken = accessToken(firstUser);
        String secondAccessToken = accessToken(secondUser);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(() -> concurrentRegister(
                    ready, start, firstAccessToken, "shared-concurrent-installation", "concurrent-token-a"));
            Future<Integer> second = executor.submit(() -> concurrentRegister(
                    ready, start, secondAccessToken, "shared-concurrent-installation", "concurrent-token-b"));

            ready.await();
            start.countDown();

            assertThat(first.get()).isEqualTo(200);
            assertThat(second.get()).isEqualTo(200);
        }

        assertThat(pushTokenRepository.findByDeviceId("shared-concurrent-installation")).isPresent();
        assertThat(pushTokenRepository.count()).isEqualTo(1);
    }

    @Test
    void pushTokenEndpointsRequireAuthenticationAndValidateInput() throws Exception {
        mockMvc.perform(put("/api/notifications/push-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "platform": "ANDROID",
                                  "deviceId": "device-a",
                                  "pushToken": "token-a"
                                }
                                """))
                .andExpect(status().isUnauthorized());

        User user = userRepository.save(new User("push-validation@example.com", null));
        register(accessToken(user), "ANDROID", "", "")
                .andExpect(status().isBadRequest());
    }

    @Test
    void accountDeletionDisablesAllPushRegistrations() throws Exception {
        User user = userRepository.save(new User("push-deleted-user@example.com", null));
        register(accessToken(user), "ANDROID", "deleted-device", "deleted-token")
                .andExpect(status().isOk());

        accountDeletionService.deleteCurrentUser(user.getId().toString());

        assertThat(pushTokenRepository.findByDeviceId("deleted-device"))
                .get()
                .extracting(UserPushToken::isEnabled)
                .isEqualTo(false);
    }

    private org.springframework.test.web.servlet.ResultActions register(
            String accessToken,
            String platform,
            String deviceId,
            String pushToken
    ) throws Exception {
        return mockMvc.perform(put("/api/notifications/push-token")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "platform": "%s",
                          "deviceId": "%s",
                          "pushToken": "%s"
                        }
                        """.formatted(platform, deviceId, pushToken)));
    }

    private int concurrentRegister(
            CountDownLatch ready,
            CountDownLatch start,
            String accessToken,
            String deviceId,
            String pushToken
    ) throws Exception {
        ready.countDown();
        start.await();
        return register(accessToken, "ANDROID", deviceId, pushToken)
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String accessToken(User user) {
        return jwtTokenProvider.issue(user.getId().toString(), Set.of("USER")).accessToken();
    }
}
