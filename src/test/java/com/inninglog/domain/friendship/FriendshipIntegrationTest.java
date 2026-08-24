package com.inninglog.domain.friendship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inninglog.domain.friendship.entity.Friendship;
import com.inninglog.domain.friendship.entity.FriendshipStatus;
import com.inninglog.domain.friendship.repository.FriendshipRepository;
import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.notification.entity.UserNotification;
import com.inninglog.domain.notification.repository.UserNotificationRepository;
import com.inninglog.domain.team.entity.KboTeam;
import com.inninglog.domain.team.repository.KboTeamRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.repository.UserRepository;
import com.inninglog.domain.user.service.AccountDeletionService;
import com.inninglog.global.security.JwtTokenProvider;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FriendshipIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KboTeamRepository teamRepository;

    @Autowired
    private FriendshipRepository friendshipRepository;

    @Autowired
    private UserNotificationRepository notificationRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private AccountDeletionService accountDeletionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void searchIsAuthenticatedPrefixBasedRelationshipAwareAndPrivacySafe() throws Exception {
        String prefix = uniqueUsername("search");
        User current = completedUser(prefix + ".me");
        User alpha = completedUser(prefix + ".alpha");
        User beta = completedUser(prefix + ".beta");
        incompleteUser(prefix + ".incomplete");
        deletedUser(prefix + ".deleted");

        mockMvc.perform(get("/api/users/search")
                        .queryParam("username", "  " + prefix.toUpperCase() + "  "))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/users/search")
                        .header("Authorization", authorization(current))
                        .queryParam("username", "  " + prefix.toUpperCase() + "  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].user.id").value(alpha.getId()))
                .andExpect(jsonPath("$[0].user.username").value(alpha.getUsername()))
                .andExpect(jsonPath("$[0].user.nickname").value(alpha.getNickname()))
                .andExpect(jsonPath("$[0].user.favoriteTeam.teamCode").value("NC"))
                .andExpect(jsonPath("$[0].user.email").doesNotExist())
                .andExpect(jsonPath("$[0].relationshipStatus").value("NONE"))
                .andExpect(jsonPath("$[0].friendshipId").value(nullValue()))
                .andExpect(jsonPath("$[1].user.id").value(beta.getId()));

        String wildcardPrefix = uniqueUsername("literal");
        User literalUnderscore = completedUser(wildcardPrefix + "_friend");
        completedUser(wildcardPrefix + "xfriend");
        mockMvc.perform(get("/api/users/search")
                        .header("Authorization", authorization(current))
                        .queryParam("username", wildcardPrefix + "_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].user.id").value(literalUnderscore.getId()));

        mockMvc.perform(get("/api/users/search")
                        .header("Authorization", authorization(current))
                        .queryParam("username", "invalid%username"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void allFriendshipEndpointsRequireAuthenticationAndValidateTheirInputs() throws Exception {
        mockMvc.perform(post("/api/friendships/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverId\": 1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/friendships"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/friendships/requests"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/friendships/{id}/accept", 1))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/friendships/{id}/reject", 1))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/friendships/{id}", 1))
                .andExpect(status().isUnauthorized());

        User user = completedUser(uniqueUsername("valid"));
        mockMvc.perform(post("/api/friendships/requests")
                        .header("Authorization", authorization(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(post("/api/friendships/{id}/accept", 0)
                        .header("Authorization", authorization(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        User incomplete = incompleteUser(uniqueUsername("profile"));
        mockMvc.perform(get("/api/friendships")
                        .header("Authorization", authorization(incomplete)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_SETUP_REQUIRED"));
    }

    @Test
    void unavailableReceiversAlwaysReturnTheSameNotFoundContract() throws Exception {
        User requester = completedUser(uniqueUsername("candidate"));
        User deletedReceiver = deletedUser(uniqueUsername("gone"));
        User incompleteReceiver = incompleteUser(uniqueUsername("unfinished"));

        sendRequestToId(requester, Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FRIEND_USER_NOT_FOUND"));
        sendRequestToId(requester, deletedReceiver.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FRIEND_USER_NOT_FOUND"));
        sendRequestToId(requester, incompleteReceiver.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FRIEND_USER_NOT_FOUND"));

        assertThat(countFriendships(requester, deletedReceiver)).isZero();
        assertThat(countFriendships(requester, incompleteReceiver)).isZero();
        assertThat(notificationsFor(deletedReceiver)).isEmpty();
        assertThat(notificationsFor(incompleteReceiver)).isEmpty();
    }

    @Test
    void sendingARequestCreatesOneCanonicalRelationshipAndOneInboxEvent() throws Exception {
        User requester = completedUser(uniqueUsername("sender"));
        User receiver = completedUser(uniqueUsername("receiver"));

        MvcResult created = sendRequest(requester, receiver)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.friend.id").value(receiver.getId()))
                .andExpect(jsonPath("$.requestedByMe").value(true))
                .andExpect(jsonPath("$.respondedAt").value(nullValue()))
                .andReturn();
        long friendshipId = responseId(created);

        sendRequest(requester, receiver)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FRIEND_REQUEST_ALREADY_SENT"));
        sendRequest(receiver, requester)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INCOMING_FRIEND_REQUEST_EXISTS"));
        sendRequest(requester, requester)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_FRIEND_REQUEST_NOT_ALLOWED"));

        assertThat(countFriendships(requester, receiver)).isEqualTo(1);
        List<UserNotification> notifications = notificationsFor(receiver);
        assertThat(notifications).hasSize(1);
        UserNotification notification = notifications.getFirst();
        assertThat(notification.getEventKey()).isEqualTo("friendship:" + friendshipId + ":requested:1");
        assertThat(notification.getNotificationType()).isEqualTo(NotificationType.FRIEND_REQUEST);
        assertThat(notification.getDataJson())
                .contains("\"friendshipId\":\"" + friendshipId + "\"")
                .contains("\"requestRevision\":\"1\"");
        assertThat(countOutboxEvents(friendshipId)).isEqualTo(1);
    }

    @Test
    void receivedRequestListContainsOnlyIncomingPendingRequestsNewestFirst() throws Exception {
        User receiver = completedUser(uniqueUsername("inbox"));
        User firstRequester = completedUser(uniqueUsername("first"));
        User secondRequester = completedUser(uniqueUsername("second"));

        long firstId = responseId(sendRequest(firstRequester, receiver).andReturn());
        long secondId = responseId(sendRequest(secondRequester, receiver).andReturn());

        mockMvc.perform(get("/api/friendships/requests")
                        .header("Authorization", authorization(receiver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(secondId))
                .andExpect(jsonPath("$[0].friend.id").value(secondRequester.getId()))
                .andExpect(jsonPath("$[0].requestedByMe").value(false))
                .andExpect(jsonPath("$[1].id").value(firstId))
                .andExpect(jsonPath("$[1].friend.id").value(firstRequester.getId()));

        mockMvc.perform(get("/api/friendships/requests")
                        .header("Authorization", authorization(firstRequester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/friendships")
                        .header("Authorization", authorization(receiver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void acceptRejectFriendListsAndSearchExposeTheCorrectLifecycle() throws Exception {
        User receiver = completedUser(uniqueUsername("owner"));
        User acceptedRequester = completedUser(uniqueUsername("accepted"));
        User rejectedRequester = completedUser(uniqueUsername("rejected"));
        long acceptedId = responseId(sendRequest(acceptedRequester, receiver).andReturn());
        long rejectedId = responseId(sendRequest(rejectedRequester, receiver).andReturn());

        accept(receiver, acceptedId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.friend.id").value(acceptedRequester.getId()))
                .andExpect(jsonPath("$.requestedByMe").value(false))
                .andExpect(jsonPath("$.respondedAt").isNotEmpty());
        accept(receiver, acceptedId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        reject(receiver, rejectedId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.respondedAt").isNotEmpty());
        reject(receiver, rejectedId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        mockMvc.perform(get("/api/friendships")
                        .header("Authorization", authorization(receiver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(acceptedId))
                .andExpect(jsonPath("$[0].friend.id").value(acceptedRequester.getId()));
        mockMvc.perform(get("/api/friendships")
                        .header("Authorization", authorization(acceptedRequester)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].friend.id").value(receiver.getId()))
                .andExpect(jsonPath("$[0].requestedByMe").value(true));
        mockMvc.perform(get("/api/friendships/requests")
                        .header("Authorization", authorization(receiver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        search(receiver, acceptedRequester.getUsername())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relationshipStatus").value("FRIEND"))
                .andExpect(jsonPath("$[0].friendshipId").value(acceptedId));
        search(receiver, rejectedRequester.getUsername())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relationshipStatus").value("NONE"))
                .andExpect(jsonPath("$[0].friendshipId").value(nullValue()));

        sendRequest(acceptedRequester, receiver)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_FRIENDS"));
        reject(receiver, acceptedId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_FRIENDSHIP_STATE"));
        accept(receiver, rejectedId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_FRIENDSHIP_STATE"));

        assertThat(notificationsFor(acceptedRequester))
                .extracting(UserNotification::getEventKey)
                .containsExactly("friendship:" + acceptedId + ":accepted:1");
    }

    @Test
    void requesterCanCancelPendingAndEitherFriendCanDeleteAcceptedRelationship() throws Exception {
        User first = completedUser(uniqueUsername("deleter"));
        User second = completedUser(uniqueUsername("target"));

        long pendingId = responseId(sendRequest(first, second).andReturn());
        deleteFriendship(first, pendingId)
                .andExpect(status().isNoContent());
        assertThat(friendshipRepository.findById(pendingId)).isEmpty();

        long acceptedId = responseId(sendRequest(first, second).andReturn());
        accept(second, acceptedId).andExpect(status().isOk());
        deleteFriendship(second, acceptedId)
                .andExpect(status().isNoContent());
        assertThat(friendshipRepository.findById(acceptedId)).isEmpty();
        mockMvc.perform(get("/api/friendships")
                        .header("Authorization", authorization(first)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        long rejectedId = responseId(sendRequest(first, second).andReturn());
        reject(second, rejectedId).andExpect(status().isOk());
        deleteFriendship(first, rejectedId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_FRIENDSHIP_STATE"));
    }

    @Test
    void rejectedPairCanBeReRequestedInReverseWithRevisionScopedNotifications() throws Exception {
        User first = completedUser(uniqueUsername("revisiona"));
        User second = completedUser(uniqueUsername("revisionb"));

        long friendshipId = responseId(sendRequest(first, second).andReturn());
        reject(second, friendshipId).andExpect(status().isOk());

        sendRequest(second, first)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(friendshipId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.friend.id").value(first.getId()))
                .andExpect(jsonPath("$.requestedByMe").value(true));
        search(first, second.getUsername())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relationshipStatus").value("REQUEST_RECEIVED"))
                .andExpect(jsonPath("$[0].friendshipId").value(friendshipId));
        accept(first, friendshipId).andExpect(status().isOk());

        Friendship friendship = friendshipRepository.findById(friendshipId).orElseThrow();
        assertThat(friendship.getRequestRevision()).isEqualTo(2);
        assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);
        assertThat(friendship.getRequester().getId()).isEqualTo(second.getId());
        assertThat(friendship.getReceiver().getId()).isEqualTo(first.getId());

        assertThat(notificationsFor(first))
                .extracting(UserNotification::getEventKey)
                .containsExactly("friendship:" + friendshipId + ":requested:2");
        assertThat(notificationsFor(second))
                .extracting(UserNotification::getEventKey)
                .containsExactly(
                        "friendship:" + friendshipId + ":accepted:2",
                        "friendship:" + friendshipId + ":requested:1");
        assertThat(notificationsFor(first).getFirst().getDataJson())
                .contains("\"requestRevision\":\"2\"");
        assertThat(countOutboxEvents(friendshipId)).isEqualTo(3);
    }

    @Test
    void onlyTheReceiverCanRespondAndUnrelatedUsersCannotEnumerateRelationships() throws Exception {
        User requester = completedUser(uniqueUsername("authrequest"));
        User receiver = completedUser(uniqueUsername("authreceive"));
        User stranger = completedUser(uniqueUsername("authother"));
        long friendshipId = responseId(sendRequest(requester, receiver).andReturn());

        accept(requester, friendshipId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FRIENDSHIP_ACTION_NOT_ALLOWED"));
        reject(requester, friendshipId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FRIENDSHIP_ACTION_NOT_ALLOWED"));
        deleteFriendship(receiver, friendshipId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FRIENDSHIP_ACTION_NOT_ALLOWED"));

        accept(stranger, friendshipId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FRIENDSHIP_NOT_FOUND"));
        reject(stranger, friendshipId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FRIENDSHIP_NOT_FOUND"));
        deleteFriendship(stranger, friendshipId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FRIENDSHIP_NOT_FOUND"));
        assertThat(friendshipRepository.findById(friendshipId))
                .get()
                .extracting(Friendship::getStatus)
                .isEqualTo(FriendshipStatus.PENDING);
    }

    @Test
    void accountDeletionRemovesFriendshipsFromBothDirectionsAndPersistsDeletedAt() throws Exception {
        User deletingUser = completedUser(uniqueUsername("deleteaccount"));
        User requestedFriend = completedUser(uniqueUsername("requestedfriend"));
        User requestingFriend = completedUser(uniqueUsername("requestingfriend"));

        long outgoingId = responseId(sendRequest(deletingUser, requestedFriend).andReturn());
        accept(requestedFriend, outgoingId).andExpect(status().isOk());
        long incomingId = responseId(sendRequest(requestingFriend, deletingUser).andReturn());
        accept(deletingUser, incomingId).andExpect(status().isOk());
        assertThat(friendshipCountForUser(deletingUser)).isEqualTo(2);

        accountDeletionService.deleteCurrentUser(deletingUser.getId().toString());

        assertThat(friendshipRepository.findById(outgoingId)).isEmpty();
        assertThat(friendshipRepository.findById(incomingId)).isEmpty();
        assertThat(friendshipCountForUser(deletingUser)).isZero();
        User persisted = userRepository.findById(deletingUser.getId()).orElseThrow();
        assertThat(persisted.isDeleted()).isTrue();
        assertThat(persisted.getDeletedAt()).isNotNull();
    }

    @Test
    void concurrentReverseRequestsCreateOneRelationshipAndOneNotification() throws Exception {
        User first = completedUser(uniqueUsername("racea"));
        User second = completedUser(uniqueUsername("raceb"));
        String firstToken = accessToken(first);
        String secondToken = accessToken(second);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ConcurrentResponse firstResponse;
        ConcurrentResponse secondResponse;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ConcurrentResponse> firstFuture = executor.submit(() -> concurrentSend(
                    firstToken, second.getId(), ready, start));
            Future<ConcurrentResponse> secondFuture = executor.submit(() -> concurrentSend(
                    secondToken, first.getId(), ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            firstResponse = firstFuture.get(10, TimeUnit.SECONDS);
            secondResponse = secondFuture.get(10, TimeUnit.SECONDS);
        }

        assertThat(List.of(firstResponse.status(), secondResponse.status()))
                .containsExactlyInAnyOrder(201, 409);
        ConcurrentResponse conflict = firstResponse.status() == 409 ? firstResponse : secondResponse;
        assertThat(conflict.body()).contains("\"code\":\"INCOMING_FRIEND_REQUEST_EXISTS\"");
        assertThat(countFriendships(first, second)).isEqualTo(1);

        Long friendshipId = jdbcTemplate.queryForObject(
                "select id from friendships where pair_key = ?",
                Long.class,
                Friendship.pairKey(first.getId(), second.getId()));
        Friendship friendship = friendshipRepository.findById(friendshipId).orElseThrow();
        assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.PENDING);
        assertThat(notificationCountForUsers(first, second)).isEqualTo(1);
        assertThat(countOutboxEvents(friendship.getId())).isEqualTo(1);
    }

    private ResultActions sendRequest(User requester, User receiver) throws Exception {
        return sendRequestToId(requester, receiver.getId());
    }

    private ResultActions sendRequestToId(User requester, Long receiverId) throws Exception {
        return mockMvc.perform(post("/api/friendships/requests")
                .header("Authorization", authorization(requester))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"receiverId\": %d}".formatted(receiverId)));
    }

    private ResultActions accept(User receiver, long friendshipId) throws Exception {
        return mockMvc.perform(post("/api/friendships/{id}/accept", friendshipId)
                .header("Authorization", authorization(receiver)));
    }

    private ResultActions reject(User receiver, long friendshipId) throws Exception {
        return mockMvc.perform(post("/api/friendships/{id}/reject", friendshipId)
                .header("Authorization", authorization(receiver)));
    }

    private ResultActions deleteFriendship(User user, long friendshipId) throws Exception {
        return mockMvc.perform(delete("/api/friendships/{id}", friendshipId)
                .header("Authorization", authorization(user)));
    }

    private ResultActions search(User user, String username) throws Exception {
        return mockMvc.perform(get("/api/users/search")
                .header("Authorization", authorization(user))
                .queryParam("username", username));
    }

    private ConcurrentResponse concurrentSend(
            String token,
            Long receiverId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        start.await(5, TimeUnit.SECONDS);
        MvcResult result = mockMvc.perform(post("/api/friendships/requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverId\": %d}".formatted(receiverId)))
                .andReturn();
        return new ConcurrentResponse(result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private User completedUser(String username) {
        User user = new User(username + "@example.com", "https://example.com/" + username + ".png");
        user.setupOnboardingUsername(username);
        user.setupOnboardingNickname("Nick " + username);
        KboTeam favoriteTeam = teamRepository.findById(1L).orElseThrow();
        user.selectInitialFavoriteTeam(favoriteTeam);
        return userRepository.saveAndFlush(user);
    }

    private User incompleteUser(String username) {
        User user = new User(username + "@example.com", null);
        user.setupOnboardingUsername(username);
        user.setupOnboardingNickname("Nick " + username);
        return userRepository.saveAndFlush(user);
    }

    private User deletedUser(String username) {
        User user = completedUser(username);
        user.softDelete(Instant.now());
        return userRepository.saveAndFlush(user);
    }

    private String authorization(User user) {
        return "Bearer " + accessToken(user);
    }

    private String accessToken(User user) {
        return jwtTokenProvider.issue(user.getId().toString(), Set.of("USER")).accessToken();
    }

    private static String uniqueUsername(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static long responseId(MvcResult result) throws Exception {
        Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
        return id.longValue();
    }

    private long countFriendships(User first, User second) {
        return jdbcTemplate.queryForObject(
                "select count(*) from friendships where pair_key = ?",
                Long.class,
                Friendship.pairKey(first.getId(), second.getId()));
    }

    private long countOutboxEvents(long friendshipId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notification_outbox where idempotency_key like ?",
                Long.class,
                "friendship:" + friendshipId + ":%");
    }

    private long friendshipCountForUser(User user) {
        return jdbcTemplate.queryForObject(
                "select count(*) from friendships where requester_id = ? or receiver_id = ?",
                Long.class,
                user.getId(),
                user.getId());
    }

    private long notificationCountForUsers(User first, User second) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notifications where user_id in (?, ?) and event_key like 'friendship:%'",
                Long.class,
                first.getId(),
                second.getId());
    }

    private List<UserNotification> notificationsFor(User user) {
        return notificationRepository.findByUserIdOrderByIdDesc(user.getId(), PageRequest.of(0, 20));
    }

    private record ConcurrentResponse(int status, String body) {
    }
}
