package com.inninglog.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.notification.repository.NotificationDeliveryTargetRepository;
import com.inninglog.domain.notification.repository.NotificationOutboxRepository;
import com.inninglog.domain.notification.repository.NotificationSettingRepository;
import com.inninglog.domain.notification.repository.UserNotificationRepository;
import com.inninglog.domain.notification.service.NotificationQueueService;
import com.inninglog.domain.notification.service.PushNotification;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.repository.UserRepository;
import com.inninglog.global.security.JwtTokenProvider;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationFeatureIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationQueueService notificationQueueService;

    @Autowired
    private UserNotificationRepository notificationRepository;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private NotificationDeliveryTargetRepository targetRepository;

    @Autowired
    private NotificationSettingRepository settingRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void clearNotificationState() {
        targetRepository.deleteAll();
        outboxRepository.deleteAll();
        notificationRepository.deleteAll();
        settingRepository.deleteAll();
    }

    @Test
    void notificationInboxSupportsCursorPaginationAndOwnerScopedReadOperations() throws Exception {
        User owner = createUser("notification-owner@example.com");
        User other = createUser("notification-other@example.com");
        String ownerToken = accessToken(owner);

        Long firstId = notify(owner, "inbox-1", NotificationType.RECORD_REMINDER);
        Long secondId = notify(owner, "inbox-2", NotificationType.RECORD_REMINDER);
        Long thirdId = notify(owner, "inbox-3", NotificationType.RECORD_REMINDER);
        Long otherId = notify(other, "other-inbox", NotificationType.RECORD_REMINDER);

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(thirdId))
                .andExpect(jsonPath("$.items[1].id").value(secondId))
                .andExpect(jsonPath("$.items[0].data.type").value("RECORD_REMINDER"))
                .andExpect(jsonPath("$.nextCursor").value(secondId))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.unreadCount").value(3));

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + ownerToken)
                        .queryParam("cursor", secondId.toString())
                        .queryParam("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(firstId))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.hasNext").value(false));

        mockMvc.perform(patch("/api/notifications/{id}/read", otherId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        assertThat(notificationRepository.findById(otherId).orElseThrow().getReadAt()).isNull();

        mockMvc.perform(patch("/api/notifications/{id}/read", secondId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(patch("/api/notifications/{id}/read", secondId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(patch("/api/notifications/read-all")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void settingsDefaultToEnabledAndPatchOnlyProvidedFields() throws Exception {
        User user = createUser("notification-settings@example.com");
        String token = accessToken(user);

        mockMvc.perform(get("/api/notification-settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameProgressEnabled").value(true))
                .andExpect(jsonPath("$.recordReminderEnabled").value(true))
                .andExpect(jsonPath("$.socialReactionEnabled").value(true));

        mockMvc.perform(patch("/api/notification-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordReminderEnabled": false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameProgressEnabled").value(true))
                .andExpect(jsonPath("$.recordReminderEnabled").value(false))
                .andExpect(jsonPath("$.socialReactionEnabled").value(true));

        mockMvc.perform(patch("/api/notification-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_SETTINGS"));

        mockMvc.perform(get("/api/notification-settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void inboxEndpointsRequireAuthenticationAndValidateCursorBounds() throws Exception {
        User user = createUser("notification-validation@example.com");
        String token = accessToken(user);

        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("cursor", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/notifications")
                        .header("Authorization", "Bearer " + token)
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disabledOptionalPushStillCreatesInboxAndMandatoryTypesStillQueue() throws Exception {
        User user = createUser("notification-preference@example.com");
        String token = accessToken(user);
        mockMvc.perform(patch("/api/notification-settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recordReminderEnabled": false}
                                """))
                .andExpect(status().isOk());

        Long reminderId = notify(user, "disabled-reminder", NotificationType.RECORD_REMINDER);
        assertThat(notificationRepository.findById(reminderId)).isPresent();
        assertThat(outboxRepository.findByNotificationId(reminderId)).isEmpty();

        Long friendRequestId = notify(user, "mandatory-friend-request", NotificationType.FRIEND_REQUEST);
        assertThat(notificationRepository.findById(friendRequestId)).isPresent();
        assertThat(outboxRepository.findByNotificationId(friendRequestId)).isPresent();
    }

    @Test
    void concurrentDuplicateDomainEventCreatesOneInboxAndOneOutbox() throws Exception {
        User user = createUser("notification-concurrent@example.com");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Long> first = executor.submit(() -> notifyConcurrently(user, ready, start));
            Future<Long> second = executor.submit(() -> notifyConcurrently(user, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get()).isEqualTo(second.get());
        }

        assertThat(notificationRepository.count()).isEqualTo(1);
        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    private Long notifyConcurrently(
            User user,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await();
        return notify(user, "same-notification-event", NotificationType.RECORD_REMINDER);
    }

    private Long notify(User user, String eventKey, NotificationType type) {
        return transactionTemplate.execute(ignored -> notificationQueueService.enqueueToUser(
                eventKey,
                user.getId(),
                new PushNotification(type, "알림 제목", "알림 내용", Map.of("resourceId", eventKey))));
    }

    private User createUser(String email) {
        return userRepository.save(new User(email, null));
    }

    private String accessToken(User user) {
        return jwtTokenProvider.issue(user.getId().toString(), Set.of("USER")).accessToken();
    }
}
