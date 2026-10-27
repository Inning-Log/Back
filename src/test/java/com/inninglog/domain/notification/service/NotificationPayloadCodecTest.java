package com.inninglog.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.inninglog.domain.notification.entity.NotificationOutbox;
import com.inninglog.domain.notification.entity.NotificationType;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NotificationPayloadCodecTest {
    private final NotificationPayloadCodec codec = new NotificationPayloadCodec(new ObjectMapper(),
            new NotificationLinkService("https://inning-log.vercel.app"));

    @Test
    void previouslyQueuedAcceptanceUsesCurrentFriendRouteAndPreservesEventIdentity() {
        String stored = codec.encode(Map.of("actorUserId", "8", "friendshipId", "3", "requestRevision", "2",
                "link", "https://inning-log.vercel.app/mypage/friends"));
        var outbox = new NotificationOutbox("friendship:3:accepted:2", 1L, 7L, NotificationType.FRIEND_ACCEPTED,
                "친구 수락", "친구가 되었습니다.", stored, Instant.now());
        var decoded = codec.decode(outbox);
        assertThat(decoded.data()).containsEntry("link", "https://inning-log.vercel.app/timeline/8")
                .containsEntry("actorUserId", "8").containsEntry("friendshipId", "3").containsEntry("requestRevision", "2");
        assertThat(outbox.getDataJson()).isEqualTo(stored);
    }

    @Test
    void otherNotificationDestinationsAndReadOnlyDataDecodingRemainUnchanged() {
        var data = Map.of("actorUserId", "8", "link", "https://inning-log.vercel.app/timeline/history/2?commentId=5");
        String stored = codec.encode(data);
        assertThat(codec.decodeData(NotificationType.TIMELINE_COMMENT, stored)).isEqualTo(data);
        assertThat(codec.decodeData(stored)).isEqualTo(data);
    }

    @Test
    void oldCommentNotificationsGetActualRecipientWithoutConfusingVideoOwnerOrActor() {
        String stored = codec.encode(Map.of("ownerUserId","8","actorUserId","9","videoId","10"));
        assertThat(codec.decodeData(NotificationType.TIMELINE_COMMENT,stored,7))
                .containsEntry("recipientUserId","7").containsEntry("audienceUserId","7")
                .containsEntry("ownerUserId","8").containsEntry("actorUserId","9");
        var outbox = new NotificationOutbox("comment:10",1L,7L,NotificationType.TIMELINE_COMMENT,"reply","body",stored,Instant.now());
        assertThat(codec.decode(outbox).data()).containsEntry("recipientUserId","7");
    }

    @Test
    void commentInboxAndQueuedPushRepairStaleIdentityAndOwnerRouteWithoutChangingStoredData() {
        String stored = codec.encode(Map.of("ownerUserId", "7", "actorUserId", "9",
                "recipientUserId", "999", "audienceUserId", "999", "gameId", "2",
                "recordId", "3", "videoId", "4", "commentId", "5",
                "link", "https://inning-log.vercel.app/timeline/9?gameId=2"));
        var outbox = new NotificationOutbox("comment:5", 1L, 7L, NotificationType.TIMELINE_COMMENT,
                "댓글", "body", stored, Instant.now());
        var inbox = codec.decodeData(NotificationType.TIMELINE_COMMENT, stored, 7);
        assertThat(inbox).containsEntry("audienceUserId", "7").containsEntry("recipientUserId", "7")
                .containsEntry("ownerUserId", "7").containsEntry("actorUserId", "9")
                .containsEntry("link", "https://inning-log.vercel.app/timeline/history/2?recordId=3&videoId=4&commentId=5");
        assertThat(codec.decode(outbox).data()).containsAllEntriesOf(inbox);
        assertThat(outbox.getDataJson()).isEqualTo(stored);
    }

    @Test
    void replyRecipientUsesVideoOwnersTimelineEvenWhenActorIsSomeoneElse() {
        String stored = codec.encode(Map.of("ownerUserId", "8", "actorUserId", "9",
                "gameId", "2", "recordId", "3", "videoId", "4", "commentId", "5"));
        assertThat(codec.decodeData(NotificationType.TIMELINE_COMMENT, stored, 7))
                .containsEntry("link", "https://inning-log.vercel.app/timeline/8?gameId=2&recordId=3&videoId=4&commentId=5")
                .containsEntry("audienceUserId", "7").containsEntry("recipientUserId", "7");
    }

    @Test
    void nonCommentInboxAlsoUsesActualAudienceWithoutInventingCommentMetadata() {
        String stored = codec.encode(Map.of("audienceUserId", "999", "gameId", "2"));
        assertThat(codec.decodeData(NotificationType.GAME_INNING_STARTED, stored, 7))
                .containsEntry("audienceUserId", "7").containsEntry("gameId", "2")
                .doesNotContainKeys("recipientUserId", "ownerUserId");
    }
}
