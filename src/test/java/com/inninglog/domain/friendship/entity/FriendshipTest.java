package com.inninglog.domain.friendship.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inninglog.domain.friendship.exception.InvalidFriendshipStateException;
import com.inninglog.domain.user.entity.User;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class FriendshipTest {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-24T01:00:00Z");
    private static final Instant RESPONDED_AT = Instant.parse("2026-08-24T02:00:00Z");
    private static final Instant REQUESTED_AGAIN_AT = Instant.parse("2026-08-25T03:00:00Z");

    @Test
    void newRequestStartsPendingWithCanonicalPairAndInitialTimestamps() {
        User requester = user(42L, "requester@example.com");
        User receiver = user(7L, "receiver@example.com");

        Friendship friendship = Friendship.request(requester, receiver, REQUESTED_AT);

        assertThat(friendship.getRequester()).isSameAs(requester);
        assertThat(friendship.getReceiver()).isSameAs(receiver);
        assertThat(friendship.getPairKey()).isEqualTo("7:42");
        assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.PENDING);
        assertThat(friendship.getRequestRevision()).isEqualTo(1);
        assertThat(friendship.getRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(friendship.getRespondedAt()).isNull();
        assertThat(friendship.getCreatedAt()).isEqualTo(REQUESTED_AT);
        assertThat(friendship.getUpdatedAt()).isEqualTo(REQUESTED_AT);
    }

    @Test
    void acceptTransitionsPendingRequestAndUpdatesOnlyResponseTimestamps() {
        Friendship friendship = Friendship.request(
                user(1L, "requester@example.com"),
                user(2L, "receiver@example.com"),
                REQUESTED_AT);

        friendship.accept(RESPONDED_AT);

        assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);
        assertThat(friendship.getRequestRevision()).isEqualTo(1);
        assertThat(friendship.getRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(friendship.getRespondedAt()).isEqualTo(RESPONDED_AT);
        assertThat(friendship.getCreatedAt()).isEqualTo(REQUESTED_AT);
        assertThat(friendship.getUpdatedAt()).isEqualTo(RESPONDED_AT);
    }

    @Test
    void rejectTransitionsPendingRequestAndUpdatesOnlyResponseTimestamps() {
        Friendship friendship = Friendship.request(
                user(1L, "requester@example.com"),
                user(2L, "receiver@example.com"),
                REQUESTED_AT);

        friendship.reject(RESPONDED_AT);

        assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.REJECTED);
        assertThat(friendship.getRequestRevision()).isEqualTo(1);
        assertThat(friendship.getRequestedAt()).isEqualTo(REQUESTED_AT);
        assertThat(friendship.getRespondedAt()).isEqualTo(RESPONDED_AT);
        assertThat(friendship.getCreatedAt()).isEqualTo(REQUESTED_AT);
        assertThat(friendship.getUpdatedAt()).isEqualTo(RESPONDED_AT);
    }

    @Test
    void terminalFriendshipsRejectAdditionalResponses() {
        Friendship accepted = Friendship.request(
                user(1L, "accepted-requester@example.com"),
                user(2L, "accepted-receiver@example.com"),
                REQUESTED_AT);
        accepted.accept(RESPONDED_AT);

        Friendship rejected = Friendship.request(
                user(3L, "rejected-requester@example.com"),
                user(4L, "rejected-receiver@example.com"),
                REQUESTED_AT);
        rejected.reject(RESPONDED_AT);

        assertThatThrownBy(() -> accepted.accept(REQUESTED_AGAIN_AT))
                .isInstanceOf(InvalidFriendshipStateException.class);
        assertThatThrownBy(() -> accepted.reject(REQUESTED_AGAIN_AT))
                .isInstanceOf(InvalidFriendshipStateException.class);
        assertThatThrownBy(() -> rejected.accept(REQUESTED_AGAIN_AT))
                .isInstanceOf(InvalidFriendshipStateException.class);
        assertThatThrownBy(() -> rejected.reject(REQUESTED_AGAIN_AT))
                .isInstanceOf(InvalidFriendshipStateException.class);
    }

    @Test
    void resendIsAllowedOnlyAfterRejection() {
        User first = user(1L, "first@example.com");
        User second = user(2L, "second@example.com");
        Friendship pending = Friendship.request(first, second, REQUESTED_AT);

        assertThatThrownBy(() -> pending.resend(second, first, REQUESTED_AGAIN_AT))
                .isInstanceOf(InvalidFriendshipStateException.class);

        pending.accept(RESPONDED_AT);

        assertThatThrownBy(() -> pending.resend(second, first, REQUESTED_AGAIN_AT))
                .isInstanceOf(InvalidFriendshipStateException.class);
    }

    @Test
    void rejectedFriendshipCanBeResentInReverseDirectionWithNextRevision() {
        User first = user(10L, "first@example.com");
        User second = user(20L, "second@example.com");
        Friendship friendship = Friendship.request(first, second, REQUESTED_AT);
        friendship.reject(RESPONDED_AT);

        friendship.resend(second, first, REQUESTED_AGAIN_AT);

        assertThat(friendship.getRequester()).isSameAs(second);
        assertThat(friendship.getReceiver()).isSameAs(first);
        assertThat(friendship.getPairKey()).isEqualTo("10:20");
        assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.PENDING);
        assertThat(friendship.getRequestRevision()).isEqualTo(2);
        assertThat(friendship.getRequestedAt()).isEqualTo(REQUESTED_AGAIN_AT);
        assertThat(friendship.getRespondedAt()).isNull();
        assertThat(friendship.getCreatedAt()).isEqualTo(REQUESTED_AT);
        assertThat(friendship.getUpdatedAt()).isEqualTo(REQUESTED_AGAIN_AT);
    }

    @Test
    void rejectedFriendshipCannotBeReusedForAnotherPair() {
        User first = user(10L, "first@example.com");
        User second = user(20L, "second@example.com");
        Friendship friendship = Friendship.request(first, second, REQUESTED_AT);
        friendship.reject(RESPONDED_AT);

        assertThatThrownBy(() -> friendship.resend(
                first,
                user(30L, "third@example.com"),
                REQUESTED_AGAIN_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different user pair");
    }

    @Test
    void pairKeyIsIndependentOfRequestDirection() {
        assertThat(Friendship.pairKey(7L, 42L)).isEqualTo("7:42");
        assertThat(Friendship.pairKey(42L, 7L)).isEqualTo("7:42");
    }

    @Test
    void requestRejectsTheSamePersistentUserEvenAcrossDifferentInstances() {
        User firstReference = user(9L, "first-reference@example.com");
        User secondReference = user(9L, "second-reference@example.com");

        assertThatThrownBy(() -> Friendship.request(firstReference, firstReference, REQUESTED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("themselves");
        assertThatThrownBy(() -> Friendship.request(firstReference, secondReference, REQUESTED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("themselves");
    }

    private static User user(Long id, String email) {
        User user = new User(email, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
