package com.inninglog.domain.auth.service;

import com.inninglog.domain.auth.repository.AuthRefreshTokenRepository;
import com.inninglog.domain.auth.repository.OAuthAccountRepository;
import com.inninglog.domain.user.entity.User;
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
    private final Clock clock;

    public AccountDeletionService(
            UserRepository userRepository,
            OAuthAccountRepository oAuthAccountRepository,
            AuthRefreshTokenRepository refreshTokenRepository,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.oAuthAccountRepository = oAuthAccountRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
    }

    @Transactional
    public void deleteCurrentUser(String subject) {
        User user = lockUser(subject);
        Instant now = clock.instant();

        refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);
        if (!user.isDeleted()) {
            oAuthAccountRepository.findAllByUserId(user.getId())
                    .forEach(account -> account.anonymizeEmail(user.getId()));
            user.softDelete(now);
        }
    }

    private User lockUser(String subject) {
        try {
            return userRepository.findByIdForUpdate(Long.valueOf(subject))
                    .orElseThrow(AuthUserNotFoundException::new);
        } catch (NumberFormatException exception) {
            throw new AuthUserNotFoundException();
        }
    }
}
