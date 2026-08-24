package com.inninglog.domain.notification.dto;

public record NotificationSettingsUpdateRequest(
        Boolean gameProgressEnabled,
        Boolean recordReminderEnabled,
        Boolean socialReactionEnabled
) {
    public boolean isEmpty() {
        return gameProgressEnabled == null
                && recordReminderEnabled == null
                && socialReactionEnabled == null;
    }
}
