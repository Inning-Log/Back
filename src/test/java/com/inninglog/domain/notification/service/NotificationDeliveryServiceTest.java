package com.inninglog.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.notification.repository.UserPushTokenRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryServiceTest {

    @Mock
    private UserPushTokenRepository pushTokenRepository;

    @Mock
    private PushGateway pushGateway;

    @Mock
    private PushTokenCleanupService pushTokenCleanupService;

    @InjectMocks
    private NotificationDeliveryService notificationDeliveryService;

    @Test
    void sendToUserReturnsWithoutCallingFirebaseWhenUserHasNoEnabledDevices() {
        when(pushTokenRepository.findEnabledPushTokensByUserId(1L)).thenReturn(List.of());

        NotificationDeliveryResult result = notificationDeliveryService.sendToUser(1L, notification());

        assertThat(result).isEqualTo(NotificationDeliveryResult.noTargets());
        verify(pushGateway, never()).send(anyListNotification(), anyList());
    }

    @Test
    void sendToUserSplitsTargetsAtFirebaseLimitAndDisablesUnregisteredTokens() {
        List<String> tokens = IntStream.range(0, 501)
                .mapToObj(index -> "token-" + index)
                .toList();
        when(pushTokenRepository.findEnabledPushTokensByUserId(1L)).thenReturn(tokens);
        when(pushGateway.send(anyListNotification(), org.mockito.ArgumentMatchers.argThat(batch -> batch.size() == 500)))
                .thenReturn(new PushBatchResult(499, 1, List.of("token-3")));
        when(pushGateway.send(anyListNotification(), org.mockito.ArgumentMatchers.argThat(batch -> batch.size() == 1)))
                .thenReturn(new PushBatchResult(1, 0, List.of()));

        NotificationDeliveryResult result = notificationDeliveryService.sendToUser(1L, notification());

        assertThat(result).isEqualTo(new NotificationDeliveryResult(501, 500, 1));
        verify(pushTokenCleanupService).disableInvalidTokens(List.of("token-3"));
    }

    private PushNotification notification() {
        return new PushNotification(
                NotificationType.GAME_PROGRESS,
                "경기가 시작됐어요",
                "응원 팀의 경기 진행 상황을 확인하세요.",
                Map.of("gameId", "42"));
    }

    private static PushNotification anyListNotification() {
        return org.mockito.ArgumentMatchers.any(PushNotification.class);
    }
}
