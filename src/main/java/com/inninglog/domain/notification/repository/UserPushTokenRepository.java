package com.inninglog.domain.notification.repository;

import com.inninglog.domain.notification.entity.UserPushToken;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserPushTokenRepository extends JpaRepository<UserPushToken, Long> {

    Optional<UserPushToken> findByPushToken(String pushToken);

    Optional<UserPushToken> findByDeviceId(String deviceId);

    @Query("""
            select token
              from UserPushToken token
             where token.user.id = :userId
               and token.enabled = true
               and token.user.deletedAt is null
             order by token.id
            """)
    List<UserPushToken> findEnabledRegistrationsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("""
            update UserPushToken token
               set token.enabled = false, token.updatedAt = :disabledAt
             where token.id = :registrationId
               and token.pushToken = :expectedPushToken
               and token.enabled = true
            """)
    int disableIfPushTokenMatches(
            @Param("registrationId") Long registrationId,
            @Param("expectedPushToken") String expectedPushToken,
            @Param("disabledAt") Instant disabledAt
    );

    @Modifying
    @Query("""
            update UserPushToken token
               set token.enabled = false, token.updatedAt = :disabledAt
             where token.user.id = :userId
               and token.enabled = true
            """)
    int disableAllByUserId(
            @Param("userId") Long userId,
            @Param("disabledAt") Instant disabledAt
    );
}
