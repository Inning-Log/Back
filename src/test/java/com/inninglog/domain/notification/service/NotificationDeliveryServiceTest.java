package com.inninglog.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationDeliveryServiceTest {

    @Mock
    private PushGateway pushGateway;

    @InjectMocks
    private NotificationDeliveryService notificationDeliveryService;

    @Test
    void deliveryReturnsWithoutCallingFirebaseWhenThereAreNoTargets() {
        PushBatchResult result = notificationDeliveryService.deliver(notification(), List.of());

        assertThat(result.targetResults()).isEmpty();
        verify(pushGateway, never()).send(anyListNotification(), anyList());
    }

    @Test
    void deliverySplitsTargetsAtFirebaseLimitAndPreservesTargetResults() {
        List<PushTarget> targets = IntStream.range(0, 501)
                .mapToObj(index -> new PushTarget(
                        (long) index, (long) index, "fid-" + index, 1L))
                .toList();
        when(pushGateway.send(anyListNotification(), org.mockito.ArgumentMatchers.argThat(batch -> batch.size() == 500)))
                .thenReturn(new PushBatchResult(IntStream.range(0, 500)
                        .mapToObj(index -> index == 3
                                ? PushTargetResult.failure(targets.get(index), PushTargetOutcome.INVALID, "UNREGISTERED")
                                : PushTargetResult.success(targets.get(index)))
                        .toList()));
        when(pushGateway.send(anyListNotification(), org.mockito.ArgumentMatchers.argThat(batch -> batch.size() == 1)))
                .thenReturn(new PushBatchResult(List.of(PushTargetResult.success(targets.get(500)))));

        PushBatchResult result = notificationDeliveryService.deliver(notification(), targets);

        assertThat(result.successCount()).isEqualTo(500);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.targetResults().get(3).outcome()).isEqualTo(PushTargetOutcome.INVALID);
    }

    private PushNotification notification() {
        return new PushNotification(
                com.inninglog.domain.notification.entity.NotificationType.GAME_INNING_STARTED,
                "경기가 시작됐어요",
                "응원 팀의 경기 진행 상황을 확인하세요.",
                java.util.Map.of("gameId", "42"));
    }

    private static PushNotification anyListNotification() {
        return org.mockito.ArgumentMatchers.any(PushNotification.class);
    }
}
