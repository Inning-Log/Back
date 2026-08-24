package com.inninglog.domain.notification.dto;

import com.inninglog.domain.notification.entity.NotificationSetting;

public record NotificationSettingsResponse(
        boolean gameProgressEnabled,
        boolean recordReminderEnabled,
        boolean socialReactionEnabled
) {
    public static NotificationSettingsResponse defaults() {
        return new NotificationSettingsResponse(true, true, true);
    }

    public static NotificationSettingsResponse from(NotificationSetting setting) {
        return new NotificationSettingsResponse(
                setting.isGameProgressEnabled(),
                setting.isRecordReminderEnabled(),
                setting.isSocialReactionEnabled());
    }
}
