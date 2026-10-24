package com.inninglog.domain.friendship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inninglog.domain.notification.entity.UserNotification;
import com.inninglog.domain.notification.repository.UserNotificationRepository;
import com.inninglog.domain.team.repository.KboTeamRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.repository.UserRepository;
import com.inninglog.global.security.JwtTokenProvider;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.notifications.frontend-origin=https://inning-log.vercel.app")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FriendRequestNotificationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired KboTeamRepository teams;
    @Autowired UserNotificationRepository notifications;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;

    @Test
    void acceptanceInboxUsesFriendTimelineForNewAndPreviouslyStoredNotifications() throws Exception {
        User requester = user();
        User receiver = user();
        long id = request(requester, receiver);
        resolve(receiver, id, "accept");
        String expected = "https://inning-log.vercel.app/timeline/" + receiver.getId();
        String response = mvc.perform(get("/api/notifications").header("Authorization", auth(requester)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].data.link").value(expected))
                .andExpect(jsonPath("$.items[0].data.actorUserId").value(receiver.getId().toString()))
                .andReturn().getResponse().getContentAsString();
        long notificationId = ((Number) JsonPath.read(response, "$.items[0].id")).longValue();
        mvc.perform(patch("/api/notifications/{id}/read", notificationId).header("Authorization", auth(requester)))
                .andExpect(status().isNoContent());
        Instant readAt = notifications.findById(notificationId).orElseThrow().getReadAt();
        String legacyData = "{\"actorUserId\":\"" + receiver.getId() + "\",\"friendshipId\":\"" + id
                + "\",\"link\":\"https://inning-log.vercel.app/mypage/friends\"}";
        jdbc.update("update notifications set data_json = ? where id = ?", legacyData, notificationId);

        mvc.perform(get("/api/notifications").header("Authorization", auth(requester)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].data.link").value(expected))
                .andExpect(jsonPath("$.items[0].data.friendshipId").value(Long.toString(id)))
                .andExpect(jsonPath("$.unreadCount").value(0));
        UserNotification stored = notifications.findById(notificationId).orElseThrow();
        assertThat(stored.getReadAt()).isEqualTo(readAt);
        assertThat(stored.getDataJson()).isEqualTo(legacyData);
    }

    @Test
    void acceptingReadsOnlyTheResolvedRequestAndRepeatedAcceptPreservesReadTimestamp() throws Exception {
        User requester = user();
        User receiver = user();
        User otherRequester = user();
        long id = request(requester, receiver);
        long otherId = request(otherRequester, receiver);
        unread(receiver, 2);

        resolve(receiver, id, "accept");
        UserNotification resolved = notification(receiver, id, 1);
        assertThat(resolved.getReadAt()).isNotNull();
        assertThat(notification(receiver, otherId, 1).getReadAt()).isNull();
        unread(receiver, 1);
        mvc.perform(get("/api/notifications").header("Authorization", auth(receiver)))
                .andExpect(jsonPath("$.items[?(@.id == " + resolved.getId() + ")].readAt").isNotEmpty());

        resolve(receiver, id, "accept");
        assertThat(notification(receiver, id, 1).getReadAt()).isEqualTo(resolved.getReadAt());
        unread(receiver, 1);
        unread(requester, 1);
    }

    @Test
    void rejectingAndRepeatingRejectResolveInboxWithoutSendingAcceptance() throws Exception {
        User requester = user();
        User receiver = user();
        long id = request(requester, receiver);
        resolve(receiver, id, "reject");
        Instant firstRead = notification(receiver, id, 1).getReadAt();
        assertThat(firstRead).isNotNull();
        resolve(receiver, id, "reject");
        assertThat(notification(receiver, id, 1).getReadAt()).isEqualTo(firstRead);
        unread(receiver, 0);
        unread(requester, 0);
    }

    @Test
    void cancellingRequestReadsReceiverNotificationAndRepeatedDeleteDoesNotChangeIt() throws Exception {
        User requester = user();
        User receiver = user();
        long id = request(requester, receiver);
        mvc.perform(delete("/api/friendships/{id}", id).header("Authorization", auth(requester)))
                .andExpect(status().isNoContent());
        Instant readAt = notification(receiver, id, 1).getReadAt();
        assertThat(readAt).isNotNull();
        unread(receiver, 0);
        mvc.perform(delete("/api/friendships/{id}", id).header("Authorization", auth(requester)))
                .andExpect(status().isNotFound());
        assertThat(notification(receiver, id, 1).getReadAt()).isEqualTo(readAt);
    }

    @Test
    void rerequestIsUnreadUntilItsOwnRevisionIsResolvedAndOtherTypesArePreserved() throws Exception {
        User requester = user();
        User receiver = user();
        long id = request(requester, receiver);
        resolve(receiver, id, "reject");
        Instant firstRead = notification(receiver, id, 1).getReadAt();
        assertThat(request(requester, receiver)).isEqualTo(id);
        jdbc.update("""
                insert into notifications(event_key,user_id,notification_type,title,data_json,created_at)
                values (?,?,'TIMELINE_COMMENT','댓글','{}',current_timestamp)
                """, "unrelated:" + UUID.randomUUID(), receiver.getId());
        assertThat(notification(receiver, id, 2).getReadAt()).isNull();
        unread(receiver, 2);

        resolve(receiver, id, "accept");
        assertThat(notification(receiver, id, 1).getReadAt()).isEqualTo(firstRead);
        assertThat(notification(receiver, id, 2).getReadAt()).isNotNull();
        unread(receiver, 1);
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=? and notification_type='TIMELINE_COMMENT' and read_at is null",
                Long.class, receiver.getId())).isEqualTo(1);
    }

    @Test
    void unauthorizedResolutionLeavesRequestUnread() throws Exception {
        User requester = user();
        User receiver = user();
        User outsider = user();
        long id = request(requester, receiver);
        mvc.perform(post("/api/friendships/{id}/accept", id).header("Authorization", auth(outsider)))
                .andExpect(status().isNotFound());
        assertThat(notification(receiver, id, 1).getReadAt()).isNull();
        unread(receiver, 1);
    }

    @Test
    void deletingRequesterReadsInvisibleRequestButPreservesOtherRequestsAndCommentNotifications() throws Exception {
        User deletingRequester = user();
        User receiver = user();
        User otherRequester = user();
        long deletedRequest = request(deletingRequester, receiver);
        long otherRequest = request(otherRequester, receiver);
        jdbc.update("""
                insert into notifications(event_key,user_id,notification_type,title,data_json,created_at)
                values (?,?,'TIMELINE_COMMENT','댓글','{}',current_timestamp)
                """, "unrelated:" + UUID.randomUUID(), receiver.getId());
        unread(receiver, 3);

        mvc.perform(delete("/api/users/me").header("Authorization", auth(deletingRequester)))
                .andExpect(status().isNoContent());

        assertThat(notification(receiver, deletedRequest, 1).getReadAt()).isNotNull();
        assertThat(notification(receiver, otherRequest, 1).getReadAt()).isNull();
        unread(receiver, 2);
        assertThat(jdbc.queryForObject("select count(*) from friendships where id = ?", Long.class, deletedRequest)).isZero();
        assertThat(users.findById(deletingRequester.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=? and notification_type='TIMELINE_COMMENT' and read_at is null",
                Long.class, receiver.getId())).isEqualTo(1);
    }

    @Test
    void accountDeletionPreservesAlreadyReadRequestTimestampAndResolvesLatestRevision() throws Exception {
        User requester = user();
        User receiver = user();
        long id = request(requester, receiver);
        resolve(receiver, id, "reject");
        Instant originalReadAt = notification(receiver, id, 1).getReadAt();
        request(requester, receiver);
        unread(receiver, 1);

        mvc.perform(delete("/api/users/me").header("Authorization", auth(requester)))
                .andExpect(status().isNoContent());

        assertThat(notification(receiver, id, 1).getReadAt()).isEqualTo(originalReadAt);
        assertThat(notification(receiver, id, 2).getReadAt()).isNotNull();
        unread(receiver, 0);
    }

    private User user() {
        String name = "fn" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User user = new User(name + "@example.com", null);
        user.setupOnboardingUsername(name);
        user.setupOnboardingNickname(name);
        user.selectInitialFavoriteTeam(teams.findById(1L).orElseThrow());
        return users.saveAndFlush(user);
    }

    private String auth(User user) {
        return "Bearer " + tokens.issue(user.getId().toString(), Set.of("USER")).accessToken();
    }

    private long request(User sender, User receiver) throws Exception {
        String response = mvc.perform(post("/api/friendships/requests")
                        .header("Authorization", auth(sender)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverId\":" + receiver.getId() + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(response, "$.id")).longValue();
    }

    private void resolve(User receiver, long id, String action) throws Exception {
        mvc.perform(post("/api/friendships/{id}/" + action, id).header("Authorization", auth(receiver)))
                .andExpect(status().isOk());
    }

    private void unread(User user, int count) throws Exception {
        mvc.perform(get("/api/notifications").header("Authorization", auth(user)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(count));
    }

    private UserNotification notification(User receiver, long id, int revision) {
        return notifications.findByUserIdOrderByIdDesc(receiver.getId(), PageRequest.of(0, 100)).stream()
                .filter(n -> n.getEventKey().equals("friendship:" + id + ":requested:" + revision))
                .findFirst().orElseThrow();
    }
}
