package com.inninglog.domain.notification.repository;

import com.inninglog.domain.notification.entity.UserNotification;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    List<UserNotification> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    List<UserNotification> findByUserIdAndIdLessThanOrderByIdDesc(
            Long userId,
            Long cursor,
            Pageable pageable
    );

    boolean existsByIdAndUserId(Long id, Long userId);

    long countByUserIdAndReadAtIsNull(Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserNotification notification
               set notification.readAt = :readAt
             where notification.id = :notificationId
               and notification.userId = :userId
               and notification.readAt is null
            """)
    int markRead(
            @Param("notificationId") Long notificationId,
            @Param("userId") Long userId,
            @Param("readAt") Instant readAt
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserNotification notification
               set notification.readAt = :readAt
             where notification.userId = :userId
               and notification.readAt is null
            """)
    int markAllRead(@Param("userId") Long userId, @Param("readAt") Instant readAt);
}
