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
import com.inninglog.global.security.JwtTokenProvider;
import java.util.Set;
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

    private String accessToken(User user) {
        return jwtTokenProvider.issue(user.getId().toString(), Set.of("USER")).accessToken();
    }
}
