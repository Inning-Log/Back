package com.inninglog.domain.auth.repository;

import com.inninglog.domain.auth.entity.AuthRefreshToken;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshToken, Long> {

    @Query("""
            select token.id as id, token.user.id as userId
            from AuthRefreshToken token
            where token.tokenHash = :tokenHash
            """)
    Optional<TokenCandidate> findCandidateByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from AuthRefreshToken token where token.id = :id")
    Optional<AuthRefreshToken> findByIdForUpdate(@Param("id") Long id);

    @Modifying(flushAutomatically = true)
    @Query("""
            update AuthRefreshToken token
            set token.revokedAt = :revokedAt
            where token.user.id = :userId and token.revokedAt is null
            """)
    int revokeAllActiveByUserId(@Param("userId") Long userId, @Param("revokedAt") Instant revokedAt);

    @Query("""
            select token.user.id as userId,
                   token.user.deletedAt as userDeletedAt,
                   token.revokedAt as revokedAt,
                   token.expiresAt as expiresAt,
                   token.generation as generation
            from AuthRefreshToken token
            where token.id = :id
            """)
    Optional<SessionState> findSessionState(@Param("id") Long id);

    interface TokenCandidate {
        Long getId();

        Long getUserId();
    }

    interface SessionState {
        Long getUserId();

        Instant getUserDeletedAt();

        Instant getRevokedAt();

        Instant getExpiresAt();

        Integer getGeneration();
    }
}
