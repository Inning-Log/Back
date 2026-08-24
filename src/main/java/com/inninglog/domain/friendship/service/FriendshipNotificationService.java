package com.inninglog.domain.friendship.service;

import com.inninglog.domain.friendship.entity.Friendship;
import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.notification.service.NotificationQueueService;
import com.inninglog.domain.notification.service.PushNotification;
import com.inninglog.domain.user.entity.User;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FriendshipNotificationService {

    private final NotificationQueueService notificationQueueService;

    public FriendshipNotificationService(NotificationQueueService notificationQueueService) {
        this.notificationQueueService = notificationQueueService;
    }

    public void friendRequestCreated(Friendship friendship) {
        User requester = friendship.getRequester();
        User receiver = friendship.getReceiver();
        notificationQueueService.enqueueToUser(
                idempotencyKey("requested", friendship),
                receiver.getId(),
                new PushNotification(
                        NotificationType.FRIEND_REQUEST,
                        "새 친구 요청이 도착했어요",
                        displayName(requester) + "님이 친구 요청을 보냈습니다.",
                        Map.of(
                                "friendshipId", friendship.getId().toString(),
                                "actorUserId", requester.getId().toString(),
                                "requestRevision", String.valueOf(friendship.getRequestRevision()),
                                "screen", "FRIEND_REQUESTS")));
    }

    public void friendRequestAccepted(Friendship friendship) {
        User requester = friendship.getRequester();
        User receiver = friendship.getReceiver();
        notificationQueueService.enqueueToUser(
                idempotencyKey("accepted", friendship),
                requester.getId(),
                new PushNotification(
                        NotificationType.FRIEND_ACCEPTED,
                        "친구 요청이 수락됐어요",
                        displayName(receiver) + "님과 친구가 되었습니다.",
                        Map.of(
                                "friendshipId", friendship.getId().toString(),
                                "actorUserId", receiver.getId().toString(),
                                "requestRevision", String.valueOf(friendship.getRequestRevision()),
                                "screen", "FRIENDS")));
    }

    private static String idempotencyKey(String event, Friendship friendship) {
        return "friendship:" + friendship.getId() + ':' + event + ':' + friendship.getRequestRevision();
    }

    private static String displayName(User user) {
        if (user.getNickname() != null && !user.getNickname().isBlank()) {
            return user.getNickname();
        }
        return '@' + user.getUsername();
    }
}
