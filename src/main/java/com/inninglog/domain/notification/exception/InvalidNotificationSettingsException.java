package com.inninglog.domain.notification.exception;

public class InvalidNotificationSettingsException extends RuntimeException {

    public InvalidNotificationSettingsException() {
        super("변경할 알림 설정을 하나 이상 선택해 주세요.");
    }
}
