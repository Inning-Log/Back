package com.inninglog.domain.user.repository;

import com.inninglog.domain.user.entity.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByIdAndDeletedAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select user
            from User user
            left join fetch user.favoriteTeam
            where user.id <> :currentUserId
              and user.deletedAt is null
              and user.onboardingCompleted = true
              and user.username like concat(:escapedUsernamePrefix, '%') escape '~'
            order by case when user.username = :usernamePrefix then 0 else 1 end,
                     user.username asc,
                     user.id asc
            """)
    List<User> searchActiveFriendCandidates(
            @Param("currentUserId") Long currentUserId,
            @Param("usernamePrefix") String usernamePrefix,
            @Param("escapedUsernamePrefix") String escapedUsernamePrefix,
            Pageable pageable
    );
}
