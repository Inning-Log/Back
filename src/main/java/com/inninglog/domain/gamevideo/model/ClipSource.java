package com.inninglog.domain.gamevideo.model;

import com.inninglog.domain.timeline.model.InningHalf;
import java.time.Instant;

/** A READY inning record video that can be placed in a merged game video. */
public record ClipSource(
        long userId, long recordId, int inning, InningHalf half, String caption, Instant recordedAt,
        Integer homeScore, Integer awayScore, String objectKey
) {}
