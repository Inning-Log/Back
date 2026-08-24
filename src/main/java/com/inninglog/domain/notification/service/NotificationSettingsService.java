package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.dto.NotificationSettingsResponse;
import com.inninglog.domain.notification.dto.NotificationSettingsUpdateRequest;
import com.inninglog.domain.notification.entity.NotificationSetting;
import com.inninglog.domain.notification.exception.InvalidNotificationSettingsException;
import com.inninglog.domain.notification.repository.NotificationSettingRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.exception.UserNotFoundException;
import com.inninglog.domain.user.repository.UserRepository;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationSettingsService {

    private final UserRepository userRepository;
    private final NotificationSettingRepository settingRepository;
    private final Clock clock;

    public NotificationSettingsService(
            UserRepository userRepository,
            NotificationSettingRepository settingRepository,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.settingRepository = settingRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NotificationSettingsResponse get(String subject) {
        Long userId = requireActiveUserId(subject);
        return settingRepository.findById(userId)
                .map(NotificationSettingsResponse::from)
                .orElseGet(NotificationSettingsResponse::defaults);
    }

    @Transactional
    public NotificationSettingsResponse update(
            String subject,
            NotificationSettingsUpdateRequest request
    ) {
        if (request.isEmpty()) {
            throw new InvalidNotificationSettingsException();
        }
        User user = requireActiveUserForUpdate(subject);
        NotificationSetting setting = settingRepository.findById(user.getId())
                .orElseGet(() -> new NotificationSetting(user.getId(), clock.instant()));
        setting.update(
                request.gameProgressEnabled(),
                request.recordReminderEnabled(),
                request.socialReactionEnabled(),
                clock.instant());
        return NotificationSettingsResponse.from(settingRepository.save(setting));
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

    private User requireActiveUserForUpdate(String subject) {
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
