package com.inninglog.domain.friendship.repository;

import com.inninglog.domain.friendship.entity.Friendship;
import com.inninglog.domain.friendship.entity.FriendshipStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select friendship from Friendship friendship where friendship.pairKey = :pairKey")
    Optional<Friendship> findByPairKeyForUpdate(@Param("pairKey") String pairKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select friendship from Friendship friendship where friendship.id = :id")
    Optional<Friendship> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select friendship
            from Friendship friendship
            join fetch friendship.requester requester
            left join fetch requester.favoriteTeam
            join fetch friendship.receiver receiver
            left join fetch receiver.favoriteTeam
            where friendship.status = :status
              and (requester.id = :userId or receiver.id = :userId)
              and requester.deletedAt is null
              and receiver.deletedAt is null
            order by friendship.respondedAt desc, friendship.id desc
            """)
    List<Friendship> findAllVisibleByUserIdAndStatus(
            @Param("userId") Long userId,
            @Param("status") FriendshipStatus status
    );

    @Query("""
            select friendship
            from Friendship friendship
            join fetch friendship.requester requester
            left join fetch requester.favoriteTeam
            join fetch friendship.receiver receiver
            left join fetch receiver.favoriteTeam
            where friendship.receiver.id = :receiverId
              and friendship.status = :status
              and requester.deletedAt is null
              and receiver.deletedAt is null
            order by friendship.requestedAt desc, friendship.id desc
            """)
    List<Friendship> findAllReceivedByReceiverIdAndStatus(
            @Param("receiverId") Long receiverId,
            @Param("status") FriendshipStatus status
    );

    @Query("""
            select friendship
            from Friendship friendship
            where (friendship.requester.id = :userId and friendship.receiver.id in :counterpartIds)
               or (friendship.receiver.id = :userId and friendship.requester.id in :counterpartIds)
            """)
    List<Friendship> findAllRelationships(
            @Param("userId") Long userId,
            @Param("counterpartIds") Collection<Long> counterpartIds
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            delete from Friendship friendship
            where friendship.requester.id = :userId or friendship.receiver.id = :userId
            """)
    int deleteAllByUserId(@Param("userId") Long userId);
}
