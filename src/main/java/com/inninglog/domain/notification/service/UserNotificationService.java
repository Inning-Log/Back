package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.dto.NotificationItemResponse;
import com.inninglog.domain.notification.dto.NotificationListResponse;
import com.inninglog.domain.notification.entity.UserNotification;
import com.inninglog.domain.notification.exception.NotificationNotFoundException;
import com.inninglog.domain.notification.repository.UserNotificationRepository;
import com.inninglog.domain.user.exception.UserNotFoundException;
import com.inninglog.domain.user.repository.UserRepository;
import java.time.Clock;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserNotificationService {

    private final UserRepository userRepository;
    private final UserNotificationRepository notificationRepository;
    private final NotificationPayloadCodec payloadCodec;
    private final Clock clock;

    public UserNotificationService(
            UserRepository userRepository,
            UserNotificationRepository notificationRepository,
            NotificationPayloadCodec payloadCodec,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.notificationRepository = notificationRepository;
        this.payloadCodec = payloadCodec;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationListResponse getNotifications(String subject, Long cursor, int size) {
        Long userId = requireActiveUserId(subject);
        PageRequest page = PageRequest.of(0, size + 1);
        List<UserNotification> fetched = cursor == null
                ? notificationRepository.findByUserIdOrderByIdDesc(userId, page)
                : notificationRepository.findByUserIdAndIdLessThanOrderByIdDesc(userId, cursor, page);

        boolean hasNext = fetched.size() > size;
        List<UserNotification> pageItems = hasNext ? fetched.subList(0, size) : fetched;
        List<NotificationItemResponse> items = pageItems.stream()
                .map(this::toResponse)
                .toList();
        Long nextCursor = hasNext ? pageItems.getLast().getId() : null;
        long unreadCount = notificationRepository.countByUserIdAndReadAtIsNull(userId);
        return new NotificationListResponse(items, nextCursor, hasNext, unreadCount);
    }

    @Transactional
    public void markRead(String subject, Long notificationId) {
        Long userId = requireActiveUserId(subject);
        int updated = notificationRepository.markRead(notificationId, userId, clock.instant());
        if (updated == 0 && !notificationRepository.existsByIdAndUserId(notificationId, userId)) {
            throw new NotificationNotFoundException();
        }
    }

    @Transactional
    public void markAllRead(String subject) {
        Long userId = requireActiveUserId(subject);
        notificationRepository.markAllRead(userId, clock.instant());
    }

    private NotificationItemResponse toResponse(UserNotification notification) {
        return new NotificationItemResponse(
                notification.getId(),
                notification.getNotificationType(),
                notification.getTitle(),
                notification.getBody(),
                payloadCodec.decodeData(notification.getDataJson()),
                notification.getReadAt(),
                notification.getCreatedAt());
    }

    private Long requireActiveUserId(String subject) {
        try {
            Long userId = Long.valueOf(subject);
            userRepository.findByIdAndDeletedAtIsNull(userId)
                    .orElseThrow(UserNotFoundException::new);
            return userId;
        } catch (NumberFormatException exception) {
            throw new UserNotFoundException();
        }
    }
}
