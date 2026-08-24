package com.inninglog.domain.notification.repository;

import com.inninglog.domain.notification.entity.NotificationDeliveryTarget;
import com.inninglog.domain.notification.entity.NotificationTargetStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationDeliveryTargetRepository extends JpaRepository<NotificationDeliveryTarget, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select target
              from NotificationDeliveryTarget target
              join fetch target.outbox outbox
             where target.status in :statuses
               and target.nextAttemptAt <= :now
             order by target.nextAttemptAt, target.id
            """)
    List<NotificationDeliveryTarget> findNextReadyForUpdate(
            @Param("statuses") Collection<NotificationTargetStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select target
              from NotificationDeliveryTarget target
              join fetch target.outbox outbox
             where outbox.id = :outboxId
               and target.status in :statuses
               and target.nextAttemptAt <= :now
             order by target.id
            """)
    List<NotificationDeliveryTarget> findReadyBatchForOutboxForUpdate(
            @Param("outboxId") Long outboxId,
            @Param("statuses") Collection<NotificationTargetStatus> statuses,
            @Param("now") Instant now,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select target
              from NotificationDeliveryTarget target
              join fetch target.outbox outbox
             where target.id in :targetIds
            """)
    List<NotificationDeliveryTarget> findAllByIdInForUpdate(@Param("targetIds") Collection<Long> targetIds);

    long countByOutbox_IdAndStatusIn(Long outboxId, Collection<NotificationTargetStatus> statuses);

    boolean existsByOutbox_IdAndStatusIn(
            Long outboxId,
            Collection<NotificationTargetStatus> statuses
    );

    long countByStatus(NotificationTargetStatus status);
}
