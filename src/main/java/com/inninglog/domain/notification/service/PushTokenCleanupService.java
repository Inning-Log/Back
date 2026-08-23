package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.repository.UserPushTokenRepository;
import java.time.Clock;
import java.util.Collection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PushTokenCleanupService {

    private final UserPushTokenRepository pushTokenRepository;
    private final Clock clock;

    public PushTokenCleanupService(UserPushTokenRepository pushTokenRepository, Clock clock) {
        this.pushTokenRepository = pushTokenRepository;
        this.clock = clock;
    }

    @Transactional
    public void disableInvalidTokens(Collection<String> pushTokens) {
        if (!pushTokens.isEmpty()) {
            pushTokenRepository.disableAllByPushTokenIn(pushTokens, clock.instant());
        }
    }
}
