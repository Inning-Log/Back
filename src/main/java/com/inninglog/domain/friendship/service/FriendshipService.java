package com.inninglog.domain.friendship.service;

import com.inninglog.domain.friendship.dto.FriendRelationshipStatus;
import com.inninglog.domain.friendship.dto.FriendUserResponse;
import com.inninglog.domain.friendship.dto.FriendshipResponse;
import com.inninglog.domain.friendship.dto.UserSearchResponse;
import com.inninglog.domain.friendship.entity.Friendship;
import com.inninglog.domain.friendship.entity.FriendshipStatus;
import com.inninglog.domain.friendship.exception.AlreadyFriendsException;
import com.inninglog.domain.friendship.exception.FriendRequestAlreadySentException;
import com.inninglog.domain.friendship.exception.FriendshipActionNotAllowedException;
import com.inninglog.domain.friendship.exception.FriendshipNotFoundException;
import com.inninglog.domain.friendship.exception.FriendshipProfileRequiredException;
import com.inninglog.domain.friendship.exception.FriendshipUserNotFoundException;
import com.inninglog.domain.friendship.exception.IncomingFriendRequestExistsException;
import com.inninglog.domain.friendship.exception.InvalidFriendshipStateException;
import com.inninglog.domain.friendship.exception.SelfFriendRequestException;
import com.inninglog.domain.friendship.repository.FriendshipRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.exception.AccountDeletedException;
import com.inninglog.domain.user.exception.UserNotFoundException;
import com.inninglog.domain.user.repository.UserRepository;
import com.inninglog.domain.user.service.UsernamePolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FriendshipService {

    static final int SEARCH_RESULT_LIMIT = 20;

    private final UserRepository userRepository;
    private final FriendshipRepository friendshipRepository;
    private final FriendshipNotificationService notificationService;
    private final Clock clock;

    public FriendshipService(
            UserRepository userRepository,
            FriendshipRepository friendshipRepository,
            FriendshipNotificationService notificationService,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.friendshipRepository = friendshipRepository;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserSearchResponse> searchUsers(String subject, String username) {
        User currentUser = findCurrentUser(subject);
        requireCompletedProfile(currentUser);
        String normalizedPrefix = UsernamePolicy.normalize(username);

        List<User> candidates = userRepository.searchActiveFriendCandidates(
                currentUser.getId(),
                normalizedPrefix,
                escapeLikePrefix(normalizedPrefix),
                PageRequest.of(0, SEARCH_RESULT_LIMIT));
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Long> candidateIds = candidates.stream().map(User::getId).toList();
        Map<Long, Friendship> relationships = friendshipRepository
                .findAllRelationships(currentUser.getId(), candidateIds)
                .stream()
                .collect(Collectors.toMap(
                        friendship -> friendship.otherUser(currentUser.getId()).getId(),
                        Function.identity()));

        return candidates.stream()
                .map(candidate -> searchResponse(currentUser.getId(), candidate, relationships.get(candidate.getId())))
                .toList();
    }

    @Transactional
    public FriendshipResponse sendRequest(String subject, Long receiverId) {
        Long requesterId = parseSubject(subject);
        if (requesterId.equals(receiverId)) {
            throw new SelfFriendRequestException();
        }

        LockedUsers lockedUsers = lockUsers(requesterId, receiverId);
        User requester = lockedUsers.byId(requesterId);
        User receiver = lockedUsers.byId(receiverId);
        requireCurrentUserAvailable(requester);
        requireFriendCandidate(receiver);

        String pairKey = Friendship.pairKey(requesterId, receiverId);
        Friendship friendship = friendshipRepository.findByPairKeyForUpdate(pairKey).orElse(null);
        Instant now = clock.instant();

        if (friendship == null) {
            friendship = friendshipRepository.save(Friendship.request(requester, receiver, now));
        } else {
            switch (friendship.getStatus()) {
                case ACCEPTED -> throw new AlreadyFriendsException();
                case PENDING -> rejectDuplicatePendingRequest(friendship, requesterId);
                case REJECTED -> friendship.resend(requester, receiver, now);
            }
        }

        notificationService.friendRequestCreated(friendship);
        return FriendshipResponse.from(friendship, requesterId);
    }

    @Transactional(readOnly = true)
    public List<FriendshipResponse> getFriends(String subject) {
        User currentUser = findCurrentUser(subject);
        requireCompletedProfile(currentUser);
        return friendshipRepository
                .findAllVisibleByUserIdAndStatus(currentUser.getId(), FriendshipStatus.ACCEPTED)
                .stream()
                .map(friendship -> FriendshipResponse.from(friendship, currentUser.getId()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FriendshipResponse> getReceivedRequests(String subject) {
        User currentUser = findCurrentUser(subject);
        requireCompletedProfile(currentUser);
        return friendshipRepository
                .findAllReceivedByReceiverIdAndStatus(currentUser.getId(), FriendshipStatus.PENDING)
                .stream()
                .map(friendship -> FriendshipResponse.from(friendship, currentUser.getId()))
                .toList();
    }

    @Transactional
    public FriendshipResponse accept(String subject, Long friendshipId) {
        User currentUser = findCurrentUser(subject);
        requireCompletedProfile(currentUser);
        Friendship friendship = findVisibleForUpdate(friendshipId, currentUser.getId());
        requireReceiver(friendship, currentUser.getId(), "Only the receiver can accept this friend request.");
        requireFriendCandidate(friendship.getRequester());

        if (friendship.getStatus() == FriendshipStatus.ACCEPTED) {
            return FriendshipResponse.from(friendship, currentUser.getId());
        }
        if (friendship.getStatus() != FriendshipStatus.PENDING) {
            throw new InvalidFriendshipStateException("A rejected friend request cannot be accepted.");
        }

        friendship.accept(clock.instant());
        notificationService.friendRequestAccepted(friendship);
        return FriendshipResponse.from(friendship, currentUser.getId());
    }

    @Transactional
    public FriendshipResponse reject(String subject, Long friendshipId) {
        User currentUser = findCurrentUser(subject);
        requireCompletedProfile(currentUser);
        Friendship friendship = findVisibleForUpdate(friendshipId, currentUser.getId());
        requireReceiver(friendship, currentUser.getId(), "Only the receiver can reject this friend request.");
        requireFriendCandidate(friendship.getRequester());

        if (friendship.getStatus() == FriendshipStatus.REJECTED) {
            return FriendshipResponse.from(friendship, currentUser.getId());
        }
        if (friendship.getStatus() != FriendshipStatus.PENDING) {
            throw new InvalidFriendshipStateException("An accepted friendship cannot be rejected as a request.");
        }

        friendship.reject(clock.instant());
        return FriendshipResponse.from(friendship, currentUser.getId());
    }

    @Transactional
    public void delete(String subject, Long friendshipId) {
        User currentUser = findCurrentUser(subject);
        requireCompletedProfile(currentUser);
        Friendship friendship = findVisibleForUpdate(friendshipId, currentUser.getId());

        if (friendship.getStatus() == FriendshipStatus.REJECTED) {
            throw new InvalidFriendshipStateException("A rejected friend request cannot be deleted.");
        }
        if (friendship.getStatus() == FriendshipStatus.PENDING && !friendship.isRequester(currentUser.getId())) {
            throw new FriendshipActionNotAllowedException(
                    "The receiver must accept or reject a pending friend request.");
        }
        friendshipRepository.delete(friendship);
    }

    private Friendship findVisibleForUpdate(Long friendshipId, Long currentUserId) {
        Friendship friendship = friendshipRepository.findByIdForUpdate(friendshipId)
                .orElseThrow(FriendshipNotFoundException::new);
        if (!friendship.isParticipant(currentUserId)) {
            // Return the same result as a missing ID so unrelated users cannot enumerate relationships.
            throw new FriendshipNotFoundException();
        }
        return friendship;
    }

    private static void requireReceiver(Friendship friendship, Long currentUserId, String message) {
        if (!friendship.isReceiver(currentUserId)) {
            throw new FriendshipActionNotAllowedException(message);
        }
    }

    private static void rejectDuplicatePendingRequest(Friendship friendship, Long requesterId) {
        if (friendship.isRequester(requesterId)) {
            throw new FriendRequestAlreadySentException();
        }
        throw new IncomingFriendRequestExistsException();
    }

    private User findCurrentUser(String subject) {
        Long userId = parseSubject(subject);
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(UserNotFoundException::new);
        requireCurrentUserAvailable(user);
        return user;
    }

    private LockedUsers lockUsers(Long currentUserId, Long candidateId) {
        Long lowId = Math.min(currentUserId, candidateId);
        Long highId = Math.max(currentUserId, candidateId);
        Map<Long, User> users = new HashMap<>();
        users.put(lowId, findLockedUser(lowId, currentUserId));
        users.put(highId, findLockedUser(highId, currentUserId));
        return new LockedUsers(users);
    }

    private User findLockedUser(Long userId, Long currentUserId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> userId.equals(currentUserId)
                        ? new UserNotFoundException()
                        : new FriendshipUserNotFoundException());
    }

    private static void requireCurrentUserAvailable(User user) {
        if (user.isDeleted()) {
            throw new AccountDeletedException();
        }
        requireCompletedProfile(user);
    }

    private static void requireCompletedProfile(User user) {
        if (!user.isOnboardingCompleted()
                || user.getUsername() == null
                || user.getUsername().isBlank()
                || user.getNickname() == null
                || user.getNickname().isBlank()) {
            throw new FriendshipProfileRequiredException();
        }
    }

    private static void requireFriendCandidate(User user) {
        if (user.isDeleted() || !user.isOnboardingCompleted()) {
            throw new FriendshipUserNotFoundException();
        }
    }

    private static Long parseSubject(String subject) {
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException exception) {
            throw new UserNotFoundException();
        }
    }

    private static String escapeLikePrefix(String prefix) {
        return prefix
                .replace("~", "~~")
                .replace("%", "~%")
                .replace("_", "~_");
    }

    private static UserSearchResponse searchResponse(
            Long currentUserId,
            User candidate,
            Friendship friendship
    ) {
        if (friendship == null || friendship.getStatus() == FriendshipStatus.REJECTED) {
            return new UserSearchResponse(FriendUserResponse.from(candidate), FriendRelationshipStatus.NONE, null);
        }
        FriendRelationshipStatus relationshipStatus = switch (friendship.getStatus()) {
            case ACCEPTED -> FriendRelationshipStatus.FRIEND;
            case PENDING -> friendship.isRequester(currentUserId)
                    ? FriendRelationshipStatus.REQUEST_SENT
                    : FriendRelationshipStatus.REQUEST_RECEIVED;
            case REJECTED -> FriendRelationshipStatus.NONE;
        };
        return new UserSearchResponse(FriendUserResponse.from(candidate), relationshipStatus, friendship.getId());
    }

    private record LockedUsers(Map<Long, User> users) {
        User byId(Long userId) {
            return users.get(userId);
        }
    }
}
