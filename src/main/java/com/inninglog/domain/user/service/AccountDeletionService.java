package com.inninglog.domain.user.service;

import com.inninglog.domain.auth.repository.AuthRefreshTokenRepository;
import com.inninglog.domain.game.repository.GameRepository;
import com.inninglog.domain.friendship.repository.FriendshipRepository;
import com.inninglog.domain.notification.repository.UserPushRegistrationRepository;
import com.inninglog.domain.oauth.repository.OAuthAccountRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.exception.UserNotFoundException;
import com.inninglog.domain.user.repository.UserRepository;
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

    public AccountDeletionService(
            UserRepository userRepository,
            OAuthAccountRepository oAuthAccountRepository,
            AuthRefreshTokenRepository refreshTokenRepository,
            UserPushRegistrationRepository pushRegistrationRepository,
            FriendshipRepository friendshipRepository,
            Clock clock,
            GameRepository gameRepository
    ) {
        this.userRepository = userRepository;
        this.oAuthAccountRepository = oAuthAccountRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.pushRegistrationRepository = pushRegistrationRepository;
        this.friendshipRepository = friendshipRepository;
        this.clock = clock;
        this.gameRepository = gameRepository;
    }

    @Transactional
    public void deleteCurrentUser(String subject) {
        User user = lockUser(subject);
        Instant now = clock.instant();

        refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);
        pushRegistrationRepository.disableAllByUserId(user.getId(), now);
        friendshipRepository.deleteAllByUserId(user.getId());
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
