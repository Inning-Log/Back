package com.inninglog.domain.friendship.entity;

import com.inninglog.domain.friendship.exception.InvalidFriendshipStateException;
import com.inninglog.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
        name = "friendships",
        uniqueConstraints = @UniqueConstraint(name = "uk_friendships_pair_key", columnNames = "pair_key"),
        indexes = {
                @Index(
                        name = "idx_friendships_receiver_status_requested",
                        columnList = "receiver_id,status,requested_at"
                ),
                @Index(
                        name = "idx_friendships_requester_status_requested",
                        columnList = "requester_id,status,requested_at"
                )
        }
)
public class Friendship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "requester_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_friendships_requester")
    )
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "receiver_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_friendships_receiver")
    )
    private User receiver;

    @Column(name = "pair_key", nullable = false, length = 100, updatable = false)
    private String pairKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FriendshipStatus status;

    @Column(name = "request_revision", nullable = false)
    private int requestRevision;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Friendship() {
    }

    private Friendship(User requester, User receiver, Instant requestedAt) {
        requireDifferentUsers(requester, receiver);
        this.requester = requester;
        this.receiver = receiver;
        this.pairKey = pairKey(requester.getId(), receiver.getId());
        this.status = FriendshipStatus.PENDING;
        this.requestRevision = 1;
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        this.createdAt = requestedAt;
        this.updatedAt = requestedAt;
    }

    public static Friendship request(User requester, User receiver, Instant requestedAt) {
        return new Friendship(requester, receiver, requestedAt);
    }

    public void resend(User requester, User receiver, Instant requestedAt) {
        if (status != FriendshipStatus.REJECTED) {
            throw new InvalidFriendshipStateException("Only a rejected friendship can be requested again.");
        }
        requireDifferentUsers(requester, receiver);
        String nextPairKey = pairKey(requester.getId(), receiver.getId());
        if (!pairKey.equals(nextPairKey)) {
            throw new IllegalArgumentException("A friendship cannot be reused for a different user pair.");
        }

        this.requester = requester;
        this.receiver = receiver;
        this.status = FriendshipStatus.PENDING;
        this.requestRevision++;
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        this.respondedAt = null;
        this.updatedAt = requestedAt;
    }

    public void accept(Instant respondedAt) {
        requirePending();
        this.status = FriendshipStatus.ACCEPTED;
        this.respondedAt = Objects.requireNonNull(respondedAt, "respondedAt must not be null");
        this.updatedAt = respondedAt;
    }

    public void reject(Instant respondedAt) {
        requirePending();
        transitionToRejected(respondedAt);
    }

    private void transitionToRejected(Instant at) {
        this.status = FriendshipStatus.REJECTED;
        this.respondedAt = Objects.requireNonNull(at, "transition time must not be null");
        this.updatedAt = at;
    }

    private void requirePending() {
        if (status != FriendshipStatus.PENDING) {
            throw new InvalidFriendshipStateException("The friendship request is not pending.");
        }
    }

    private static void requireDifferentUsers(User requester, User receiver) {
        Objects.requireNonNull(requester, "requester must not be null");
        Objects.requireNonNull(receiver, "receiver must not be null");
        if (Objects.equals(requester.getId(), receiver.getId())) {
            throw new IllegalArgumentException("A user cannot create a friendship with themselves.");
        }
    }

    public static String pairKey(Long firstUserId, Long secondUserId) {
        Objects.requireNonNull(firstUserId, "firstUserId must not be null");
        Objects.requireNonNull(secondUserId, "secondUserId must not be null");
        long low = Math.min(firstUserId, secondUserId);
        long high = Math.max(firstUserId, secondUserId);
        return low + ":" + high;
    }

    public boolean isParticipant(Long userId) {
        return requester.getId().equals(userId) || receiver.getId().equals(userId);
    }

    public boolean isRequester(Long userId) {
        return requester.getId().equals(userId);
    }

    public boolean isReceiver(Long userId) {
        return receiver.getId().equals(userId);
    }

    public User otherUser(Long userId) {
        if (isRequester(userId)) {
            return receiver;
        }
        if (isReceiver(userId)) {
            return requester;
        }
        throw new IllegalArgumentException("The user is not part of this friendship.");
    }

    public Long getId() {
        return id;
    }

    public User getRequester() {
        return requester;
    }

    public User getReceiver() {
        return receiver;
    }

    public String getPairKey() {
        return pairKey;
    }

    public FriendshipStatus getStatus() {
        return status;
    }

    public int getRequestRevision() {
        return requestRevision;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
