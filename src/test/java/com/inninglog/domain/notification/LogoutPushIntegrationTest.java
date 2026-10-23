package com.inninglog.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inninglog.domain.auth.service.AuthSessionService;
import com.inninglog.domain.notification.dto.PushRegistrationRequest;
import com.inninglog.domain.notification.entity.DevicePlatform;
import com.inninglog.domain.notification.repository.UserPushRegistrationRepository;
import com.inninglog.domain.notification.service.PushRegistrationService;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.repository.UserRepository;
import java.util.UUID;
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
class LogoutPushIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AuthSessionService sessions;
    @Autowired PushRegistrationService registrations;
    @Autowired UserPushRegistrationRepository repository;

    @Test
    void optionalInstallationLogoutDisablesOnlyOwnedInstallationAndRevokesSession() throws Exception {
        User user = user();
        String first = register(user), second = register(user);
        var session = sessions.createSession(user);
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + session.accessToken().accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"installationId\":\"" + first + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(repository.findByInstallationId(first).orElseThrow().isEnabled()).isFalse();
        assertThat(repository.findByInstallationId(second).orElseThrow().isEnabled()).isTrue();
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + session.accessToken().accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutCannotDisableAnotherUsersInstallation() throws Exception {
        User user = user(), other = user();
        String fid = register(other);
        var session = sessions.createSession(user);
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + session.accessToken().accessToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"installationId\":\"" + fid + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(repository.findByInstallationId(fid).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void logoutAllDisablesAllOwnedInstallationsButPreservesOthers() throws Exception {
        User user = user(), other = user();
        String first = register(user), second = register(user), foreign = register(other);
        var session = sessions.createSession(user);
        mvc.perform(post("/api/auth/logout-all").header("Authorization", "Bearer " + session.accessToken().accessToken()))
                .andExpect(status().isNoContent());
        assertThat(repository.findByInstallationId(first).orElseThrow().isEnabled()).isFalse();
        assertThat(repository.findByInstallationId(second).orElseThrow().isEnabled()).isFalse();
        assertThat(repository.findByInstallationId(foreign).orElseThrow().isEnabled()).isTrue();
    }

    private User user() { return users.save(new User(UUID.randomUUID() + "@logout.example", null)); }
    private String register(User user) {
        String fid = UUID.randomUUID().toString();
        registrations.register(user.getId().toString(), new PushRegistrationRequest(DevicePlatform.WEB, fid));
        return fid;
    }
}
