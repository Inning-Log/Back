package com.inninglog.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inninglog.domain.notification.entity.UserPushRegistration;
import com.inninglog.domain.notification.repository.UserPushRegistrationRepository;
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
    private UserPushRegistrationRepository registrationRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AccountDeletionService accountDeletionService;

    @BeforeEach
    void clearPushRegistrations() {
        registrationRepository.deleteAll();
    }

    @Test
    void fidCanBeRegisteredRefreshedAndDisabled() throws Exception {
        User user = userRepository.save(new User("push-owner@example.com", null));
        String accessToken = accessToken(user);

        register(accessToken, "WEB", "fid-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("WEB"))
                .andExpect(jsonPath("$.installationId").value("fid-a"))
                .andExpect(jsonPath("$.enabled").value(true));

        register(accessToken, "WEB", "fid-a")
                .andExpect(status().isOk());

        assertThat(registrationRepository.findByInstallationId("fid-a")).isPresent();
        assertThat(registrationRepository.count()).isEqualTo(1);

        mockMvc.perform(delete("/api/notifications/push-registration")
                        .header("Authorization", "Bearer " + accessToken)
                        .queryParam("installationId", "fid-a"))
                .andExpect(status().isNoContent());

        assertThat(registrationRepository.findByInstallationId("fid-a"))
                .get()
                .extracting(UserPushRegistration::isEnabled)
                .isEqualTo(false);
    }

    @Test
    void sameInstallationMovesToTheNewUserAndLateLogoutCannotDisableTheNewOwner() throws Exception {
        User firstUser = userRepository.save(new User("installation-first@example.com", null));
        User secondUser = userRepository.save(new User("installation-second@example.com", null));

        register(accessToken(firstUser), "WEB", "shared-installation")
                .andExpect(status().isOk());
        register(accessToken(secondUser), "WEB", "shared-installation")
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/notifications/push-registration")
                        .header("Authorization", "Bearer " + accessToken(firstUser))
                        .queryParam("installationId", "shared-installation"))
                .andExpect(status().isNoContent());

        UserPushRegistration registration = registrationRepository
                .findByInstallationId("shared-installation")
                .orElseThrow();
        assertThat(registration.getUserId()).isEqualTo(secondUser.getId());
        assertThat(registration.isEnabled()).isTrue();
        assertThat(registrationRepository.count()).isEqualTo(1);
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
                    ready, start, firstAccessToken, "shared-concurrent-installation"));
            Future<Integer> second = executor.submit(() -> concurrentRegister(
                    ready, start, secondAccessToken, "shared-concurrent-installation"));

            ready.await();
            start.countDown();

            assertThat(first.get()).isEqualTo(200);
            assertThat(second.get()).isEqualTo(200);
        }

        assertThat(registrationRepository.findByInstallationId("shared-concurrent-installation")).isPresent();
        assertThat(registrationRepository.count()).isEqualTo(1);
    }

    @Test
    void pushRegistrationEndpointsRequireAuthenticationAndValidateInput() throws Exception {
        mockMvc.perform(put("/api/notifications/push-registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "platform": "WEB",
                                  "installationId": "fid-a"
                                }
                                """))
                .andExpect(status().isUnauthorized());

        User user = userRepository.save(new User("push-validation@example.com", null));
        register(accessToken(user), "WEB", "")
                .andExpect(status().isBadRequest());
    }

    @Test
    void nativeAndroidPlatformRemainsSupported() throws Exception {
        User user = userRepository.save(new User("android-push@example.com", null));

        register(accessToken(user), "ANDROID", "android-fid")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("ANDROID"));
    }

    @Test
    void accountDeletionDisablesAllPushRegistrations() throws Exception {
        User user = userRepository.save(new User("push-deleted-user@example.com", null));
        register(accessToken(user), "WEB", "deleted-installation")
                .andExpect(status().isOk());

        accountDeletionService.deleteCurrentUser(user.getId().toString());

        assertThat(registrationRepository.findByInstallationId("deleted-installation"))
                .get()
                .extracting(UserPushRegistration::isEnabled)
                .isEqualTo(false);
    }

    private org.springframework.test.web.servlet.ResultActions register(
            String accessToken,
            String platform,
            String installationId
    ) throws Exception {
        return mockMvc.perform(put("/api/notifications/push-registration")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "platform": "%s",
                          "installationId": "%s"
                        }
                        """.formatted(platform, installationId)));
    }

    private int concurrentRegister(
            CountDownLatch ready,
            CountDownLatch start,
            String accessToken,
            String installationId
    ) throws Exception {
        ready.countDown();
        start.await();
        return register(accessToken, "WEB", installationId)
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String accessToken(User user) {
        return jwtTokenProvider.issue(user.getId().toString(), Set.of("USER")).accessToken();
    }
}
