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
}
