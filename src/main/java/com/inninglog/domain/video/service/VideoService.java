package com.inninglog.domain.video.service;

import com.inninglog.domain.game.exception.GameException;
import com.inninglog.domain.game.service.GameUserAccess;
import com.inninglog.domain.timeline.model.InningRecord;
import com.inninglog.domain.timeline.repository.TimelineRepository;
import com.inninglog.domain.video.config.VideoProperties;
import com.inninglog.domain.video.dto.VideoResponse;
import com.inninglog.domain.video.dto.VideoUploadRequest;
import com.inninglog.domain.video.dto.VideoUploadResponse;
import com.inninglog.domain.video.model.InningRecordVideo;
import com.inninglog.domain.video.model.VideoStatus;
import com.inninglog.domain.video.repository.VideoRepository;
import com.inninglog.domain.video.storage.VideoStorage;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Inning record videos. The client uploads straight to S3 with a presigned PUT URL, then calls complete so the
 * server can confirm the object exists with the declared size and type before it is shown to anyone.
 */
@Service
public class VideoService {
    private static final Logger log = LoggerFactory.getLogger(VideoService.class);
    private static final Map<String, String> EXTENSIONS = Map.of(
            "video/mp4", "mp4", "video/quicktime", "mov", "video/webm", "webm");

    private final VideoRepository videos;
    private final TimelineRepository records;
    private final GameUserAccess access;
    private final VideoStorage storage;
    private final VideoProperties properties;
    private final Clock clock;

    public VideoService(VideoRepository videos, TimelineRepository records, GameUserAccess access, VideoStorage storage,
                        VideoProperties properties, Clock clock) {
        this.videos = videos;
        this.records = records;
        this.access = access;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public VideoUploadResponse requestUpload(String subject, long recordId, VideoUploadRequest request) {
        var user = access.require(subject, true);
        String contentType = request.contentType().trim().toLowerCase(Locale.ROOT);
        if (!properties.allowedContentTypes().contains(contentType))
            throw new IllegalArgumentException("지원하지 않는 영상 형식입니다.");
        if (request.contentLength() > properties.maxSize().toBytes())
            throw new GameException(HttpStatus.CONTENT_TOO_LARGE, "VIDEO_TOO_LARGE",
                    "영상 크기는 최대 " + properties.maxSize().toMegabytes() + "MB입니다.");
        var record = requireOwnedRecord(user.getId(), recordId);
        videos.lockRecord(record.id());
        var existing = videos.findActive(record.id()).orElse(null);
        if (existing != null && existing.status() == VideoStatus.READY)
            throw GameException.conflict("VIDEO_ALREADY_EXISTS", "이미 영상이 있습니다. 기존 영상을 삭제한 뒤 다시 올려 주세요.");
        // An unfinished upload is replaced by the new attempt.
        if (existing != null) discard(existing);
        String key = objectKey(user.getId(), record.id(), contentType);
        var upload = storage.presignUpload(key, contentType, request.contentLength(), properties.uploadUrlTtl());
        long id = videos.insertPending(record.id(), key, contentType, request.contentLength(), clock.instant(), upload.expiresAt());
        return new VideoUploadResponse(id, record.id(), upload.url(), upload.method(), upload.headers(), upload.expiresAt());
    }

    // A rejected upload must still commit its cleanup, so domain errors do not roll back.
    @Transactional(noRollbackFor = GameException.class)
    public VideoResponse complete(String subject, long recordId) {
        var user = access.require(subject, true);
        var record = requireOwnedRecord(user.getId(), recordId);
        videos.lockRecord(record.id());
        var video = videos.findActive(record.id()).orElseThrow(() -> GameException.notFound("VIDEO_NOT_FOUND"));
        if (video.status() == VideoStatus.PENDING) {
            if (!clock.instant().isBefore(video.uploadExpiresAt().plus(VideoCleanupService.COMPLETION_GRACE))) {
                discard(video);
                throw GameException.conflict("VIDEO_UPLOAD_EXPIRED", "업로드 가능 시간이 지났습니다. 영상을 다시 업로드해 주세요.");
            }
            var stored = storage.head(video.objectKey()).orElseThrow(() ->
                    GameException.conflict("VIDEO_NOT_UPLOADED", "업로드된 영상을 찾을 수 없습니다. 업로드를 완료한 뒤 다시 시도해 주세요."));
            String storedType = stored.contentType() == null ? "" : stored.contentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (stored.sizeBytes() != video.sizeBytes() || !storedType.equals(video.contentType())) {
                discard(video);
                throw GameException.conflict("VIDEO_UPLOAD_MISMATCH", "업로드한 파일의 크기나 형식이 요청과 다릅니다. 다시 업로드해 주세요.");
            }
            videos.markReady(video.id(), clock.instant());
            video = videos.findActive(record.id()).orElseThrow();
        }
        return withDownloadUrl(video);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VideoResponse get(String subject, long recordId) {
        var viewer = access.require(subject, false);
        var record = records.find(recordId).filter(found -> found.deletedAt() == null)
                .orElseThrow(() -> GameException.notFound("RECORD_NOT_FOUND"));
        records.visibleOwner(viewer.getId(), record.userId()).orElseThrow(() -> GameException.notFound("RECORD_NOT_FOUND"));
        var video = videos.findActive(record.id()).orElseThrow(() -> GameException.notFound("VIDEO_NOT_FOUND"));
        if (video.status() == VideoStatus.READY) return withDownloadUrl(video);
        // Friends never see unfinished uploads; the owner sees the PENDING state without a URL.
        if (record.userId() != viewer.getId()) throw GameException.notFound("VIDEO_NOT_FOUND");
        return VideoResponse.from(video, null, null);
    }

    @Transactional
    public void delete(String subject, long recordId) {
        var user = access.require(subject, true);
        var record = records.find(recordId).orElseThrow(() -> GameException.notFound("RECORD_NOT_FOUND"));
        if (record.userId() != user.getId()) throw GameException.notFound("RECORD_NOT_FOUND");
        videos.lockRecord(record.id());
        deleteForRecord(record.id());
    }

    /** Removes every active video of a record. Called when the record itself is deleted. */
    @Transactional
    public void deleteForRecord(long recordId) {
        videos.findAllActive(recordId).forEach(this::discard);
    }

    private VideoResponse withDownloadUrl(InningRecordVideo video) {
        var download = storage.presignDownload(video.objectKey(), properties.downloadUrlTtl());
        return VideoResponse.from(video, download.url(), download.expiresAt());
    }

    private void discard(InningRecordVideo video) {
        videos.delete(video.id(), clock.instant());
        afterCommit(() -> {
            try {
                storage.delete(video.objectKey());
            } catch (RuntimeException error) {
                // The deleted row durably queues a retry, including a final sweep after the PUT deadline.
                log.warn("Video deletion queued for retry: {}", video.id());
            }
        });
    }

    private InningRecord requireOwnedRecord(long userId, long recordId) {
        var record = records.find(recordId).filter(found -> found.deletedAt() == null)
                .orElseThrow(() -> GameException.notFound("RECORD_NOT_FOUND"));
        if (record.userId() != userId) throw GameException.notFound("RECORD_NOT_FOUND");
        return record;
    }

    private String objectKey(long userId, long recordId, String contentType) {
        return properties.keyPrefix() + "/users/" + userId + "/records/" + recordId + "/" + UUID.randomUUID()
                + "." + EXTENSIONS.getOrDefault(contentType, "bin");
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
