package com.inninglog.domain.user.service;

import com.inninglog.domain.auth.repository.AuthRefreshTokenRepository;
import com.inninglog.domain.game.repository.GameRepository;
import com.inninglog.domain.friendship.repository.FriendshipRepository;
import com.inninglog.domain.notification.repository.UserPushRegistrationRepository;
import com.inninglog.domain.notification.repository.UserNotificationRepository;
import com.inninglog.domain.oauth.repository.OAuthAccountRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.exception.UserNotFoundException;
import com.inninglog.domain.user.repository.UserRepository;
import com.inninglog.domain.video.repository.VideoRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountDeletionService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oAuthAccountRepository;
    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final UserPushRegistrationRepository pushRegistrationRepository;
    private final FriendshipRepository friendshipRepository;
    private final Clock clock;
    private final GameRepository gameRepository;
    private final UserNotificationRepository notificationRepository;
    private final VideoRepository videoRepository;

    public AccountDeletionService(
            UserRepository userRepository,
            OAuthAccountRepository oAuthAccountRepository,
            AuthRefreshTokenRepository refreshTokenRepository,
            UserPushRegistrationRepository pushRegistrationRepository,
            FriendshipRepository friendshipRepository,
            Clock clock,
            GameRepository gameRepository,
            UserNotificationRepository notificationRepository,
            VideoRepository videoRepository
    ) {
        this.userRepository = userRepository;
        this.oAuthAccountRepository = oAuthAccountRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.pushRegistrationRepository = pushRegistrationRepository;
        this.friendshipRepository = friendshipRepository;
        this.clock = clock;
        this.gameRepository = gameRepository;
        this.notificationRepository = notificationRepository;
        this.videoRepository = videoRepository;
    }

    @Transactional
    public void deleteCurrentUser(String subject) {
        User user = lockUser(subject);
        Instant now = clock.instant();

        refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);
        pushRegistrationRepository.disableAllByUserId(user.getId(), now);
        var requestNotificationIds = notificationRepository.findUnreadRequestIdsForParticipant(user.getId());
        friendshipRepository.deleteAllByUserId(user.getId());
        // Lock relationships before notifications, matching accept/reject/delete. Updating inbox first
        // would invert that order and could deadlock with a concurrent resolution of the same request.
        for (int start = 0; start < requestNotificationIds.size(); start += 1000) {
            notificationRepository.markSelectedRead(
                    requestNotificationIds.subList(start, Math.min(start + 1000, requestNotificationIds.size())), now);
        }
        // Commit the deletion queue with withdrawal; storage outages must not prevent withdrawal.
        videoRepository.deleteForUser(user.getId(), now);
        gameRepository.deleteUserLogs(user.getId(), now);
        if (!user.isDeleted()) {
            oAuthAccountRepository.findAllByUserId(user.getId())
                    .forEach(account -> account.anonymizeEmail(user.getId()));
            user.softDelete(now);
        }
    }

    private User lockUser(String subject) {
        try {
            return userRepository.findByIdForUpdate(Long.valueOf(subject))
                    .orElseThrow(UserNotFoundException::new);
        } catch (NumberFormatException exception) {
            throw new UserNotFoundException();
        }
    }
}
