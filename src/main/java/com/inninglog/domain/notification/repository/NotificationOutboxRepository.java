package com.inninglog.domain.notification.repository;

import com.inninglog.domain.notification.entity.NotificationOutbox;
import com.inninglog.domain.notification.entity.NotificationOutboxStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    Optional<NotificationOutbox> findByNotificationId(Long notificationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select outbox from NotificationOutbox outbox where outbox.id = :id")
    Optional<NotificationOutbox> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select outbox
              from NotificationOutbox outbox
             where outbox.status = :status
             order by outbox.createdAt, outbox.id
            """)
    List<NotificationOutbox> findNextForUpdate(
            @Param("status") NotificationOutboxStatus status,
            Pageable pageable
    );

    long countByStatus(NotificationOutboxStatus status);
}
