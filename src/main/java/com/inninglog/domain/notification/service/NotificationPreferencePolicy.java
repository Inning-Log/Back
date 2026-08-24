package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.entity.NotificationSetting;
import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.notification.repository.NotificationSettingRepository;
import org.springframework.stereotype.Component;

@Component
public class NotificationPreferencePolicy {

    private final NotificationSettingRepository settingRepository;

    public NotificationPreferencePolicy(NotificationSettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    public boolean isPushEnabled(Long userId, NotificationType type) {
        NotificationSetting setting = settingRepository.findById(userId).orElse(null);
        if (setting == null) {
            return true;
        }
        return switch (type) {
            case GAME_INNING_STARTED, GAME_INNING_ENDED, GAME_SCORE_CHANGED ->
                    setting.isGameProgressEnabled();
            case RECORD_REMINDER -> setting.isRecordReminderEnabled();
            case TIMELINE_COMMENT, TIMELINE_REACTION -> setting.isSocialReactionEnabled();
            case FRIEND_REQUEST, FRIEND_ACCEPTED,
                    GENERATED_VIDEO_READY, GENERATED_VIDEO_FAILED -> true;
        };
    }
}
