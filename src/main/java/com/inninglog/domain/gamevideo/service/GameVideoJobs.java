package com.inninglog.domain.gamevideo.service;

import com.inninglog.domain.gamevideo.model.GameVideo;
import com.inninglog.domain.gamevideo.model.GameVideoStatus;
import com.inninglog.domain.gamevideo.model.SegmentRender;
import com.inninglog.domain.gamevideo.model.SegmentStatus;
import com.inninglog.domain.gamevideo.queue.GameVideoQueue;
import com.inninglog.domain.gamevideo.repository.GameVideoRepository;
import com.inninglog.domain.video.storage.VideoStorage;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * State transitions driven by the worker. Lock order is always segment render, then game video,
 * the same as when a video is requested.
 *
 * <pre>
 * job queue    -> {"type":"RENDER_SEGMENT","renderKey","outputKey","segment":{...}}
 *                 {"type":"MERGE_GAME","gameVideoId","outputKey","segmentKeys":[...]}
 * result queue <- {"type":"SEGMENT_RESULT","renderKey","status":"READY|FAILED","durationSeconds","error"}
 *                 {"type":"MERGE_RESULT","gameVideoId","status":"READY|FAILED","durationSeconds","error"}
 * </pre>
 */
@Service
public class GameVideoJobs {
    private static final Logger log = LoggerFactory.getLogger(GameVideoJobs.class);

    private final GameVideoRepository videos;
    private final GameVideoQueue queue;
    private final GameVideoNotifier notifier;
    private final VideoStorage storage;
    private final ObjectMapper mapper;
    private final Clock clock;

    public GameVideoJobs(GameVideoRepository videos, GameVideoQueue queue, GameVideoNotifier notifier,
                         VideoStorage storage, ObjectMapper mapper, Clock clock) {
        this.videos = videos;
        this.queue = queue;
        this.notifier = notifier;
        this.storage = storage;
        this.mapper = mapper;
        this.clock = clock;
    }

    // ---- outgoing jobs (sent after commit, so the worker never sees uncommitted rows) ----

    @Transactional(propagation = Propagation.MANDATORY)
    public void publishRender(SegmentRender render, Map<String, Object> segment) {
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "RENDER_SEGMENT");
        body.put("renderKey", render.renderKey());
        body.put("outputKey", render.objectKey());
        body.put("segment", segment);
        sendAfterCommit(mapper.writeValueAsString(body), "segment " + render.id());
    }

    /** Moves a RENDERING video whose segments are all READY to MERGING and asks the worker to join them. */
    @Transactional
    public boolean startMergeIfReady(long videoId) {
        var video = videos.lockActive(videoId).orElse(null);
        if (video == null || video.status() != GameVideoStatus.RENDERING) return false;
        List<SegmentRender> segments = videos.segments(videoId);
        if (segments.stream().anyMatch(segment -> segment.status() != SegmentStatus.READY)) return false;
        videos.markMerging(videoId, clock.instant());
        var body = new LinkedHashMap<String, Object>();
        body.put("type", "MERGE_GAME");
        body.put("gameVideoId", videoId);
        body.put("outputKey", video.objectKey());
        body.put("segmentKeys", segments.stream().map(SegmentRender::objectKey).toList());
        sendAfterCommit(mapper.writeValueAsString(body), "merge " + videoId);
        return true;
    }

    // ---- incoming results ----

    /** Applies one result message. Unknown or repeated results are ignored so the message can be acknowledged. */
    @Transactional
    public void handleResult(String body) {
        JsonNode message = mapper.readTree(body);
        String type = message.path("type").asString("");
        boolean ready = "READY".equals(message.path("status").asString(""));
        String error = message.path("error").asString("WORKER_FAILED");
        switch (type) {
            case "SEGMENT_RESULT" -> {
                String renderKey = message.path("renderKey").asString("");
                if (ready) segmentReady(renderKey, duration(message));
                else segmentFailed(renderKey, error);
            }
            case "MERGE_RESULT" -> {
                long videoId = message.path("gameVideoId").asLong(0);
                if (ready) mergeReady(videoId, duration(message));
                else mergeFailed(videoId, error);
            }
            default -> log.warn("Ignoring game video result of unknown type");
        }
    }

    @Transactional
    public void segmentReady(String renderKey, BigDecimal duration) {
        var render = videos.lockRender(renderKey).orElse(null);
        if (render == null) return;
        videos.markRenderReady(render.id(), duration, clock.instant());
        for (long videoId : videos.renderingVideosUsing(render.id())) startMergeIfReady(videoId);
    }

    @Transactional
    public void segmentFailed(String renderKey, String reason) {
        var render = videos.lockRender(renderKey).orElse(null);
        if (render == null || !videos.markRenderFailed(render.id(), reason, clock.instant())) return;
        for (long videoId : videos.renderingVideosUsing(render.id())) {
            var video = videos.lockActive(videoId).orElse(null);
            if (video == null || video.status() != GameVideoStatus.RENDERING) continue;
            fail(video, "SEGMENT_FAILED");
        }
    }

    @Transactional
    public void mergeReady(long videoId, BigDecimal duration) {
        var video = videos.lockActive(videoId).orElse(null);
        if (video == null) {
            // Deleted while merging: the file the worker just wrote belongs to nobody.
            videos.find(videoId).ifPresent(deleted -> deleteObjectAfterCommit(deleted.objectKey()));
            return;
        }
        if (video.status() != GameVideoStatus.MERGING) return;
        videos.markReady(videoId, duration, clock.instant());
        notifier.ready(video);
    }

    @Transactional
    public void mergeFailed(long videoId, String reason) {
        var video = videos.lockActive(videoId).orElse(null);
        if (video == null || video.status() != GameVideoStatus.MERGING) return;
        fail(video, reason);
    }

    void deleteObjectAfterCommit(String key) {
        afterCommit(() -> {
            try {
                storage.delete(key);
            } catch (RuntimeException error) {
                log.warn("Game video object was not deleted ({})", error.getClass().getSimpleName());
            }
        });
    }

    private void fail(GameVideo video, String reason) {
        videos.markFailed(video.id(), reason, clock.instant());
        notifier.failed(video);
    }

    private void sendAfterCommit(String body, String label) {
        afterCommit(() -> {
            try {
                queue.sendJob(body);
            } catch (RuntimeException error) {
                // The recovery scheduler re-sends lost merges and times out lost segment renders.
                log.warn("Game video job was not queued: {} ({})", label, error.getClass().getSimpleName());
            }
        });
    }

    private static BigDecimal duration(JsonNode message) {
        JsonNode value = message.path("durationSeconds");
        return value.isNumber() ? value.decimalValue().setScale(3, java.math.RoundingMode.HALF_UP) : null;
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() { action.run(); }
        });
    }
}
