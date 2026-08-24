package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.config.NotificationDispatchProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.firebase", name = "enabled", havingValue = "true")
public class NotificationDispatchScheduler {

    private final NotificationDispatchService dispatchService;
    private final NotificationDispatchProperties properties;

    public NotificationDispatchScheduler(
            NotificationDispatchService dispatchService,
            NotificationDispatchProperties properties
    ) {
        this.dispatchService = dispatchService;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${app.firebase.dispatch.fixed-delay:5s}",
            initialDelayString = "${app.firebase.dispatch.initial-delay:10s}")
    public void dispatchReadyNotifications() {
        for (int processed = 0; processed < properties.maxDispatchesPerTick(); processed++) {
            if (!dispatchService.dispatchNext()) {
                return;
            }
        }
    }
}
