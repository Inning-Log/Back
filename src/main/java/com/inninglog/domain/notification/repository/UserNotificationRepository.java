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

    // Capture IDs before the relationship rows disappear. The deleting account's user lock prevents
    // a new friend request involving that account from being created during this transaction.
    @Query(value = """
            select n.id from notifications n join friendships f
              on n.user_id = f.receiver_id
             and n.event_key = concat('friendship:', cast(f.id as varchar), ':requested:', cast(f.request_revision as varchar))
             where n.notification_type = 'FRIEND_REQUEST' and n.read_at is null
               and (f.requester_id = :userId or f.receiver_id = :userId)
            """, nativeQuery = true)
    List<Long> findUnreadRequestIdsForParticipant(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true)
    @Query("""
            update UserNotification n set n.readAt = :readAt
             where n.id in :ids and n.readAt is null
            """)
    int markSelectedRead(@Param("ids") List<Long> ids, @Param("readAt") Instant readAt);

    @Modifying(flushAutomatically = true)
    @Query("""
            update UserNotification n set n.readAt = :readAt
             where n.userId = :userId and n.eventKey = :eventKey and n.readAt is null
            """)
    int markEventRead(@Param("userId") Long userId, @Param("eventKey") String eventKey,
                      @Param("readAt") Instant readAt);

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
