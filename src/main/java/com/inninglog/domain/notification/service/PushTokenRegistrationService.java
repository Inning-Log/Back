package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.dto.PushTokenRegistrationRequest;
import com.inninglog.domain.notification.dto.PushTokenResponse;
import com.inninglog.domain.notification.entity.PushRegistrationLock;
import com.inninglog.domain.notification.entity.UserPushToken;
import com.inninglog.domain.notification.repository.PushRegistrationLockRepository;
import com.inninglog.domain.notification.repository.UserPushTokenRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.exception.UserNotFoundException;
import com.inninglog.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PushTokenRegistrationService {

    private final UserRepository userRepository;
    private final UserPushTokenRepository pushTokenRepository;
    private final PushRegistrationLockRepository registrationLockRepository;
    private final Clock clock;

    public PushTokenRegistrationService(
            UserRepository userRepository,
            UserPushTokenRepository pushTokenRepository,
            PushRegistrationLockRepository registrationLockRepository,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.pushTokenRepository = pushTokenRepository;
        this.registrationLockRepository = registrationLockRepository;
        this.clock = clock;
    }

    @Transactional
    public PushTokenResponse register(String subject, PushTokenRegistrationRequest request) {
        String deviceId = request.deviceId().trim();
        String pushToken = request.pushToken().trim();
        Instant now = clock.instant();

        lockRegistrations();
        User user = findActiveUserForUpdate(subject);

        Optional<UserPushToken> tokenRegistration = pushTokenRepository.findByPushToken(pushToken);
        Optional<UserPushToken> deviceRegistration = pushTokenRepository.findByDeviceId(deviceId);

        UserPushToken registration;
        if (tokenRegistration.isPresent()) {
            registration = tokenRegistration.get();
            if (deviceRegistration.isPresent()
                    && !deviceRegistration.get().getId().equals(registration.getId())) {
                pushTokenRepository.delete(deviceRegistration.get());
                pushTokenRepository.flush();
            }
            registration.register(user, request.platform(), deviceId, pushToken, now);
        } else if (deviceRegistration.isPresent()) {
            registration = deviceRegistration.get();
            registration.register(user, request.platform(), deviceId, pushToken, now);
        } else {
            registration = new UserPushToken(user, request.platform(), deviceId, pushToken, now);
        }

        return PushTokenResponse.from(pushTokenRepository.save(registration));
    }

    @Transactional
    public void disable(String subject, String deviceId) {
        lockRegistrations();
        User user = findActiveUserForUpdate(subject);
        pushTokenRepository.findByDeviceId(deviceId.trim())
                .filter(token -> token.getUserId().equals(user.getId()))
                .ifPresent(token -> token.disable(clock.instant()));
    }

    private void lockRegistrations() {
        registrationLockRepository.findByIdForUpdate(PushRegistrationLock.GLOBAL_LOCK_ID)
                .orElseThrow(() -> new IllegalStateException("Push registration lock row is missing."));
    }

    private User findActiveUserForUpdate(String subject) {
        try {
            User user = userRepository.findByIdForUpdate(Long.valueOf(subject))
                    .orElseThrow(UserNotFoundException::new);
            if (user.isDeleted()) {
                throw new UserNotFoundException();
            }
            return user;
        } catch (NumberFormatException exception) {
            throw new UserNotFoundException();
        }
    }
}
