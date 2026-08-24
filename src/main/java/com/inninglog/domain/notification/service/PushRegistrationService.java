package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.dto.PushRegistrationRequest;
import com.inninglog.domain.notification.dto.PushRegistrationResponse;
import com.inninglog.domain.notification.entity.PushRegistrationLock;
import com.inninglog.domain.notification.entity.UserPushRegistration;
import com.inninglog.domain.notification.repository.PushRegistrationLockRepository;
import com.inninglog.domain.notification.repository.UserPushRegistrationRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.exception.UserNotFoundException;
import com.inninglog.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PushRegistrationService {

    private final UserRepository userRepository;
    private final UserPushRegistrationRepository registrationRepository;
    private final PushRegistrationLockRepository registrationLockRepository;
    private final Clock clock;

    public PushRegistrationService(
            UserRepository userRepository,
            UserPushRegistrationRepository registrationRepository,
            PushRegistrationLockRepository registrationLockRepository,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.registrationRepository = registrationRepository;
        this.registrationLockRepository = registrationLockRepository;
        this.clock = clock;
    }

    @Transactional
    public PushRegistrationResponse register(String subject, PushRegistrationRequest request) {
        String installationId = request.installationId().trim();
        Instant now = clock.instant();

        lockRegistrations();
        User user = findActiveUserForUpdate(subject);

        UserPushRegistration registration = registrationRepository.findByInstallationId(installationId)
                .map(existing -> {
                    existing.register(user, request.platform(), installationId, now);
                    return existing;
                })
                .orElseGet(() -> new UserPushRegistration(user, request.platform(), installationId, now));

        return PushRegistrationResponse.from(registrationRepository.save(registration));
    }

    @Transactional
    public void disable(String subject, String installationId) {
        lockRegistrations();
        User user = findActiveUserForUpdate(subject);
        registrationRepository.findByInstallationId(installationId.trim())
                .filter(registration -> registration.getUserId().equals(user.getId()))
                .ifPresent(registration -> registration.disable(clock.instant()));
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
