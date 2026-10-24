package com.inninglog.domain.game.model;

/** Active record counts are separate from the availability of a verified video. */
public record GameRecordingSummary(long recordCount, long readyVideoCount) {
    public static final GameRecordingSummary EMPTY = new GameRecordingSummary(0, 0);

    public String recordingState() {
        if (readyVideoCount > 0) return "READY";
        return recordCount == 0 ? "NONE" : "UNAVAILABLE";
    }
}
