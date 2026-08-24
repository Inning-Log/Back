package com.inninglog.domain.notification.service;

import java.util.List;

public interface PushGateway {

    PushBatchResult send(PushNotification notification, List<PushTarget> targets);
}
