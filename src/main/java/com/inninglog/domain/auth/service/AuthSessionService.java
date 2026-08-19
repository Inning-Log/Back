package com.inninglog.domain.auth.service;

import com.inninglog.domain.auth.entity.AuthRefreshToken;
import com.inninglog.domain.auth.exception.InvalidRefreshTokenException;
import com.inninglog.domain.auth.repository.AuthRefreshTokenRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.entity.UserRole;
import com.inninglog.domain.user.exception.AccountDeletedException;
import com.inninglog.domain.user.exception.UserNotFoundException;
import com.inninglog.domain.user.repository.UserRepository;
import com.inninglog.global.security.JwtTokenProvider;
import com.inninglog.global.security.RefreshTokenCodec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthSessionService {

    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenCodec refreshTokenCodec;
    private final Clock clock;
    private final Duration refreshTokenExpiration;

    public AuthSessionService(
            AuthRefreshTokenRepository refreshTokenRepository,
            UserRepository userRepository,
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenCodec refreshTokenCodec,
            Clock clock,
            @Value("${app.auth.refresh-token-expiration:30d}") Duration refreshTokenExpiration
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenCodec = refreshTokenCodec;
        this.clock = clock;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    @Transactional
    public SessionTokens createSession(User user) {
        if (user.isDeleted()) {
            throw new AccountDeletedException();
        }

        Instant now = clock.instant();
        String rawRefreshToken = refreshTokenCodec.generate();
        Instant refreshExpiresAt = now.plus(refreshTokenExpiration);
        AuthRefreshToken session = refreshTokenRepository.saveAndFlush(new AuthRefreshToken(
                user,
                refreshTokenCodec.hash(rawRefreshToken),
                refreshExpiresAt,
                now));

        return issueTokens(user, session, rawRefreshToken);
    }

    @Transactional
    public SessionTokens refresh(String rawRefreshToken) {
        String presentedHash = refreshTokenCodec.hash(rawRefreshToken);
        AuthRefreshTokenRepository.TokenCandidate candidate = refreshTokenRepository
                .findCandidateByTokenHash(presentedHash)
                .orElseThrow(InvalidRefreshTokenException::new);

        User user = userRepository.findByIdForUpdate(candidate.getUserId())
                .orElseThrow(InvalidRefreshTokenException::new);
        AuthRefreshToken session = refreshTokenRepository.findByIdForUpdate(candidate.getId())
                .orElseThrow(InvalidRefreshTokenException::new);
        Instant now = clock.instant();

        if (user.isDeleted()
                || !session.getUserId().equals(user.getId())
                || !session.isActiveAt(now)
                || !refreshTokenCodec.matches(session.getTokenHash(), rawRefreshToken)) {
            throw new InvalidRefreshTokenException();
        }

        String rotatedRefreshToken = refreshTokenCodec.generate();
        Instant refreshExpiresAt = now.plus(refreshTokenExpiration);
        session.rotate(refreshTokenCodec.hash(rotatedRefreshToken), refreshExpiresAt, now);

        return issueTokens(user, session, rotatedRefreshToken);
    }

    @Transactional
    public void logout(String subject, Long sessionId) {
        if (sessionId == null) {
            return;
        }
        User user = lockUser(subject);
        AuthRefreshToken session = refreshTokenRepository.findByIdForUpdate(sessionId)
                .orElse(null);
        if (session != null && session.getUserId().equals(user.getId())) {
            session.revoke(clock.instant());
        }
    }

    @Transactional
    public void logoutAll(String subject) {
        User user = lockUser(subject);
        refreshTokenRepository.revokeAllActiveByUserId(user.getId(), clock.instant());
    }

    private SessionTokens issueTokens(User user, AuthRefreshToken session, String refreshToken) {
        JwtTokenProvider.IssuedToken accessToken = jwtTokenProvider.issueSession(
                String.valueOf(user.getId()),
                Set.of(UserRole.USER.name()),
                session.getId(),
                session.getGeneration());
        return new SessionTokens(accessToken, refreshToken, session.getExpiresAt());
    }

    private User lockUser(String subject) {
        try {
            User user = userRepository.findByIdForUpdate(Long.valueOf(subject))
                    .orElseThrow(UserNotFoundException::new);
            if (user.isDeleted()) {
                throw new AccountDeletedException();
            }
            return user;
        } catch (NumberFormatException exception) {
            throw new UserNotFoundException();
        }
    }

    public record SessionTokens(
            JwtTokenProvider.IssuedToken accessToken,
            String refreshToken,
            Instant refreshTokenExpiresAt
    ) {
    }
}
