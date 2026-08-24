package com.inninglog.domain.notification.service;

public record PushTargetResult(
        Long deliveryTargetId,
        Long registrationId,
        PushTargetOutcome outcome,
        String errorCode
) {

    public static PushTargetResult success(PushTarget target) {
        return new PushTargetResult(
                target.deliveryTargetId(),
                target.registrationId(),
                PushTargetOutcome.SUCCESS,
                null);
    }

    public static PushTargetResult failure(PushTarget target, PushTargetOutcome outcome, String errorCode) {
        return new PushTargetResult(
                target.deliveryTargetId(),
                target.registrationId(),
                outcome,
                errorCode);
    }
}
