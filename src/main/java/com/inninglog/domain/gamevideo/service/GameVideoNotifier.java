package com.inninglog.domain.gamevideo.service;

import com.inninglog.domain.gamevideo.model.GameVideo;
import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.notification.service.NotificationLinkService;
import com.inninglog.domain.notification.service.NotificationQueueService;
import com.inninglog.domain.notification.service.PushNotification;
import java.util.LinkedHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Tells the requester that their merged game video finished or failed. */
@Component
public class GameVideoNotifier {
    private final NotificationQueueService notifications;
    private final NotificationLinkService links;
    private final JdbcTemplate jdbc;

    public GameVideoNotifier(NotificationQueueService notifications, NotificationLinkService links, JdbcTemplate jdbc) {
        this.notifications = notifications;
        this.links = links;
        this.jdbc = jdbc;
    }

    public void ready(GameVideo video) {
        send(video, NotificationType.GENERATED_VIDEO_READY, "경기 영상이 완성됐어요", "지금 저장하고 공유해 보세요.");
    }

    public void failed(GameVideo video) {
        send(video, NotificationType.GENERATED_VIDEO_FAILED, "경기 영상을 만들지 못했어요", "잠시 후 다시 시도해 주세요.");
    }

    private void send(GameVideo video, NotificationType type, String title, String body) {
        // A withdrawn requester gets nothing. Checked here because a failure inside the queue service
        // would mark the whole result transaction rollback-only.
        Integer active = jdbc.queryForObject("select count(*) from app_users where id = ? and deleted_at is null",
                Integer.class, video.userId());
        if (active == null || active == 0) return;
        var data = new LinkedHashMap<String, String>();
        data.put("gameId", String.valueOf(video.gameId()));
        data.put("gameVideoId", String.valueOf(video.id()));
        String link = links.gameVideo(video.gameId(), video.id());
        if (link != null) data.put("link", link);
        notifications.enqueueToUser("game-video:" + video.id() + ":" + type.name(), video.userId(),
                new PushNotification(type, title, body, data));
    }
}
