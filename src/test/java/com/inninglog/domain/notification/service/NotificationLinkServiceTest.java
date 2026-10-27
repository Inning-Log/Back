package com.inninglog.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.inninglog.domain.notification.entity.NotificationType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationLinkServiceTest {
    @Test
    void incompleteOrInvalidCommentIdentityPreservesOriginalLink() {
        var links = new NotificationLinkService("https://inning-log.vercel.app");
        for (String value : new String[]{"0", "-1", "5?gameId=2", "9223372036854775808"}) {
            var data = Map.of("gameId", "2", "recordId", "3", "videoId", "4", "commentId", value,
                    "ownerUserId", "8", "recipientUserId", "7", "link", "https://inning-log.vercel.app/home/notifications");
            assertThat(links.forNotification(NotificationType.TIMELINE_COMMENT, data)).isEqualTo(data);
        }
        var incomplete = Map.of("ownerUserId", "8", "recipientUserId", "7");
        assertThat(links.forNotification(NotificationType.TIMELINE_COMMENT, incomplete)).isEqualTo(incomplete);
    }

    @Test
    void commentLinkIsNotFabricatedWithoutConfiguredOrigin() {
        var data = Map.of("gameId", "2", "recordId", "3", "videoId", "4", "commentId", "5",
                "ownerUserId", "8", "recipientUserId", "7");
        assertThat(new NotificationLinkService("").forNotification(NotificationType.TIMELINE_COMMENT, data))
                .isEqualTo(data);
    }

    @Test
    void followsExistingFrontRoutesAndCarriesIndividualVideoIdentity() {
        var links = new NotificationLinkService("https://inning-log.vercel.app/");
        assertThat(links.game(12L)).isEqualTo("https://inning-log.vercel.app/timeline?gameId=12");
        assertThat(links.video(2L, 3L, 4L, 5L))
                .isEqualTo("https://inning-log.vercel.app/timeline/history/2?recordId=3&videoId=4&commentId=5");
        assertThat(links.video(2L, 3L, 4L, 5L, 7L, 7L))
                .isEqualTo("https://inning-log.vercel.app/timeline/history/2?recordId=3&videoId=4&commentId=5");
        assertThat(links.video(2L, 3L, 4L, 5L, 7L, 8L))
                .isEqualTo("https://inning-log.vercel.app/timeline/7?gameId=2&recordId=3&videoId=4&commentId=5");
        assertThat(links.friendshipData(Map.of("friendshipId", "6", "actorUserId", "8"), true))
                .containsEntry("friendshipId", "6").containsEntry("actorUserId", "8")
                .containsEntry("link", "https://inning-log.vercel.app/timeline/8");
        assertThat(links.friendshipData(Map.of("friendshipId", "6", "actorUserId", "8"), false))
                .containsEntry("link", "https://inning-log.vercel.app/home/notifications?tab=friends");
    }

    @Test
    void legacyOrInvalidActorFallsBackWithoutTreatingItAsUrlContent() {
        var links = new NotificationLinkService("https://inning-log.vercel.app");
        assertThat(links.friendshipData(Map.of("friendshipId", "6"), true))
                .containsEntry("link", "https://inning-log.vercel.app/mypage/friends");
        for (String actor : new String[]{"0", "-1", "1?gameId=2", "//elsewhere.example", "9223372036854775808"}) {
            assertThat(links.friendshipData(Map.of("actorUserId", actor), true))
                    .containsEntry("link", "https://inning-log.vercel.app/mypage/friends");
        }
    }

    @Test
    void unsetOriginOmitsLinksWithoutDroppingEventData() {
        var links = new NotificationLinkService("");
        assertThat(links.game(1L)).isNull();
        assertThat(links.video(2L, 3L, 4L, 5L, 7L, 8L)).isNull();
        assertThat(links.friendshipData(Map.of("friendshipId", "1"), false))
                .isEqualTo(Map.of("friendshipId", "1"));
    }

    @Test
    void refusesNonHttpsCredentialsPathsQueriesAndFragmentsInOrigin() {
        for (String origin : new String[]{"http://example.com", "https://u:p@example.com", "https://example.com/path",
                "https://example.com?next=evil", "https://example.com#fragment", "javascript:alert(1)"}) {
            assertThatThrownBy(() -> new NotificationLinkService(origin)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
