package com.inninglog.domain.notification.repository;

import com.inninglog.domain.notification.entity.UserPushToken;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserPushTokenRepository extends JpaRepository<UserPushToken, Long> {

    Optional<UserPushToken> findByPushToken(String pushToken);

    Optional<UserPushToken> findByUser_IdAndDeviceId(Long userId, String deviceId);

    List<UserPushToken> findAllByUser_IdAndEnabledTrue(Long userId);

    @Query("""
            select token.pushToken
              from UserPushToken token
             where token.user.id = :userId
               and token.enabled = true
               and token.user.deletedAt is null
            """)
    List<String> findEnabledPushTokensByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("""
            update UserPushToken token
               set token.enabled = false, token.updatedAt = :disabledAt
             where token.pushToken in :pushTokens
            """)
    int disableAllByPushTokenIn(
            @Param("pushTokens") Collection<String> pushTokens,
            @Param("disabledAt") Instant disabledAt
    );
}
