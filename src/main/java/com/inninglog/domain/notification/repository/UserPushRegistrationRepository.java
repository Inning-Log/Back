package com.inninglog.domain.notification.repository;

import com.inninglog.domain.notification.entity.UserPushRegistration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserPushRegistrationRepository extends JpaRepository<UserPushRegistration, Long> {

    Optional<UserPushRegistration> findByInstallationId(String installationId);

    @Query("""
            select registration
              from UserPushRegistration registration
             where registration.user.id = :userId
               and registration.enabled = true
               and registration.user.deletedAt is null
             order by registration.id
            """)
    List<UserPushRegistration> findEnabledRegistrationsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("""
            update UserPushRegistration registration
               set registration.enabled = false,
                   registration.updatedAt = :disabledAt,
                   registration.registrationRevision = registration.registrationRevision + 1
             where registration.id = :registrationId
               and registration.installationId = :expectedInstallationId
               and registration.registrationRevision = :expectedRevision
               and registration.enabled = true
            """)
    int disableIfInstallationIdMatches(
            @Param("registrationId") Long registrationId,
            @Param("expectedInstallationId") String expectedInstallationId,
            @Param("expectedRevision") long expectedRevision,
            @Param("disabledAt") Instant disabledAt
    );

    @Modifying
    @Query("""
            update UserPushRegistration registration
               set registration.enabled = false, registration.updatedAt = :disabledAt
             where registration.user.id = :userId
               and registration.enabled = true
            """)
    int disableAllByUserId(
            @Param("userId") Long userId,
            @Param("disabledAt") Instant disabledAt
    );
}
