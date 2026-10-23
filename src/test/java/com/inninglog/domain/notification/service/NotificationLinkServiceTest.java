package com.inninglog.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationLinkServiceTest {
    @Test
    void followsExistingFrontRoutesAndCarriesIndividualVideoIdentity() {
        var links = new NotificationLinkService("https://inning-log.vercel.app/");
        assertThat(links.game(12L)).isEqualTo("https://inning-log.vercel.app/timeline?gameId=12");
        assertThat(links.video(2L, 3L, 4L, 5L))
                .isEqualTo("https://inning-log.vercel.app/timeline/history/2?recordId=3&videoId=4&commentId=5");
        assertThat(links.friendshipData(Map.of("friendshipId", "6"), true))
                .containsEntry("friendshipId", "6").containsEntry("link", "https://inning-log.vercel.app/mypage/friends");
    }

    @Test
    void unsetOriginOmitsLinksWithoutDroppingEventData() {
        var links = new NotificationLinkService("");
        assertThat(links.game(1L)).isNull();
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
