package com.inninglog.domain.notification.repository;

import com.inninglog.domain.notification.entity.PushRegistrationLock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushRegistrationLockRepository extends JpaRepository<PushRegistrationLock, Short> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select registrationLock from PushRegistrationLock registrationLock where registrationLock.id = :id")
    Optional<PushRegistrationLock> findByIdForUpdate(@Param("id") short id);
}
