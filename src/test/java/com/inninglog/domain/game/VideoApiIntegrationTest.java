package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inninglog.domain.friendship.entity.Friendship;
import com.inninglog.domain.friendship.repository.FriendshipRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.service.AccountDeletionService;
import com.inninglog.domain.video.storage.VideoStorage;
import com.inninglog.domain.video.repository.VideoRepository;
import com.inninglog.domain.video.service.VideoCleanupService;
import com.inninglog.domain.video.service.VideoService;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

// Not @Transactional: S3 deletes run after commit, which a rolled-back test transaction never reaches.
// Committed rows therefore go to a dedicated in-memory database so other game tests keep a clean schedule.
@Import(VideoApiIntegrationTest.StorageConfig.class)
class VideoApiIntegrationTest extends GameIntegrationSupport {
    private static final List<String> OPPONENTS = List.of("KT", "NC", "SSG", "KIA", "DOO");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        if (System.getenv("GAME_TEST_JDBC_URL") == null)
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:video_api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @Autowired FakeVideoStorage storage;
    @Autowired FriendshipRepository friendships;
    @Autowired VideoRepository videos;
    @Autowired VideoCleanupService cleanup;
    @Autowired VideoService videoService;
    @Autowired AccountDeletionService accountDeletion;
    @Autowired PlatformTransactionManager transactionManager;

    @TestConfiguration
    static class StorageConfig {
        @Bean @Primary FakeVideoStorage fakeVideoStorage() { return new FakeVideoStorage(); }
    }

    /** In-memory stand-in for S3. Tests simulate the client's PUT with {@link #store}. */
    static class FakeVideoStorage implements VideoStorage {
        final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
        final List<String> presigned = Collections.synchronizedList(new ArrayList<>());
        final Set<String> failedDeletes = ConcurrentHashMap.newKeySet();
        final List<String> deleted = Collections.synchronizedList(new ArrayList<>());

        @Override
        public PresignedUrl presignUpload(String key, String contentType, long contentLength, Duration ttl) {
            presigned.add(key);
            return new PresignedUrl("https://bucket.example/" + key + "?signed", "PUT",
                    Map.of("content-type", contentType), NOW.plus(ttl));
        }

        @Override
        public PresignedUrl presignDownload(String key, Duration ttl) {
            return new PresignedUrl("https://bucket.example/" + key + "?download", "GET", Map.of(), NOW.plus(ttl));
        }

        @Override
        public Optional<StoredObject> head(String key) { return Optional.ofNullable(objects.get(key)); }

        @Override
        public void delete(String key) {
            deleted.add(key);
            if (failedDeletes.contains(key)) throw new IllegalStateException("Simulated storage outage");
            objects.remove(key);
        }

        void store(String key, long size, String type) { objects.put(key, new StoredObject(size, type)); }

        String lastKey() { return presigned.getLast(); }
    }

    @Test
    void uploadCompleteReadAndDeleteLifecycle() throws Exception {
        var owner = user();
        long recordId = record(owner);

        var upload = uploadUrl(owner, recordId, "video/mp4", 1_000);
        assertThat(upload.path("method").stringValue()).isEqualTo("PUT");
        assertThat(upload.path("uploadUrl").stringValue()).startsWith("https://bucket.example/");
        assertThat(upload.path("headers").path("content-type").stringValue()).isEqualTo("video/mp4");
        String key = storage.lastKey();
        assertThat(key).startsWith("inning-records/users/" + owner.getId() + "/records/" + recordId + "/").endsWith(".mp4");
        assertThat(recordStatus(owner, recordId)).isEqualTo("PENDING");

        mvc.perform(post(path(recordId) + "/complete").with(as(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VIDEO_NOT_UPLOADED"));

        storage.store(key, 1_000, "video/mp4");
        mvc.perform(post(path(recordId) + "/complete").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.downloadUrl").value("https://bucket.example/" + key + "?download"));
        // Repeated completion is harmless.
        mvc.perform(post(path(recordId) + "/complete").with(as(owner))).andExpect(status().isOk());
        assertThat(recordStatus(owner, recordId)).isEqualTo("READY");

        mvc.perform(post(path(recordId) + "/upload-url").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("video/mp4", 10)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VIDEO_ALREADY_EXISTS"));

        mvc.perform(get(path(recordId)).with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sizeBytes").value(1_000))
                .andExpect(jsonPath("$.contentType").value("video/mp4"));

        mvc.perform(delete(path(recordId)).with(as(owner))).andExpect(status().isNoContent());
        assertThat(storage.objects).doesNotContainKey(key);
        mvc.perform(get(path(recordId)).with(as(owner)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
        mvc.perform(delete(path(recordId)).with(as(owner))).andExpect(status().isNoContent());
        assertThat(recordStatus(owner, recordId)).isEqualTo("UNAVAILABLE");

        // After deleting, a new video can be uploaded for the same record.
        uploadUrl(owner, recordId, "video/quicktime", 20);
        assertThat(storage.lastKey()).endsWith(".mov");
    }

    @Test
    void validatesTypeSizeAndUploadContents() throws Exception {
        var owner = user();
        long recordId = record(owner);
        mvc.perform(post(path(recordId) + "/upload-url").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(body("image/png", 10))).andExpect(status().isBadRequest());
        mvc.perform(post(path(recordId) + "/upload-url").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(body("video/mp4", 0))).andExpect(status().isBadRequest());
        mvc.perform(post(path(recordId) + "/upload-url").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("video/mp4", 501L * 1024 * 1024)))
                .andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("VIDEO_TOO_LARGE"));
        mvc.perform(post(path(999_999_999) + "/upload-url").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(body("video/mp4", 10))).andExpect(status().isNotFound());

        // A second request before completion replaces the unfinished upload.
        uploadUrl(owner, recordId, "video/mp4", 100);
        String abandoned = storage.lastKey();
        storage.store(abandoned, 100, "video/mp4");
        uploadUrl(owner, recordId, "video/mp4", 100);
        String key = storage.lastKey();
        assertThat(storage.objects).doesNotContainKey(abandoned);

        storage.store(key, 99, "video/mp4");
        mvc.perform(post(path(recordId) + "/complete").with(as(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VIDEO_UPLOAD_MISMATCH"));
        assertThat(storage.objects).doesNotContainKey(key);
        mvc.perform(post(path(recordId) + "/complete").with(as(owner))).andExpect(status().isNotFound());
        assertThat(recordStatus(owner, recordId)).isEqualTo("UNAVAILABLE");
    }

    @Test
    void friendsReadOnlyReadyVideosAndNeverWrite() throws Exception {
        var owner = user();
        var friend = user();
        var stranger = user();
        befriend(owner, friend);
        long recordId = record(owner);

        uploadUrl(owner, recordId, "video/mp4", 10);
        String key = storage.lastKey();
        mvc.perform(get(path(recordId)).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.downloadUrl").doesNotExist());
        mvc.perform(get(path(recordId)).with(as(friend))).andExpect(status().isNotFound());

        storage.store(key, 10, "video/mp4");
        mvc.perform(post(path(recordId) + "/complete").with(as(friend))).andExpect(status().isNotFound());
        mvc.perform(post(path(recordId) + "/complete").with(as(owner))).andExpect(status().isOk());

        mvc.perform(get(path(recordId)).with(as(friend))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        mvc.perform(get(path(recordId)).with(as(stranger))).andExpect(status().isNotFound());
        mvc.perform(delete(path(recordId)).with(as(friend))).andExpect(status().isNotFound());
        mvc.perform(post(path(recordId) + "/upload-url").with(as(friend)).contentType(MediaType.APPLICATION_JSON)
                .content(body("video/mp4", 10))).andExpect(status().isNotFound());
        assertThat(storage.objects).containsKey(key);
        mvc.perform(get(path(recordId))).andExpect(status().isUnauthorized());
    }

    @Test
    void deletingTheRecordRemovesItsVideo() throws Exception {
        var owner = user();
        long recordId = record(owner);
        uploadUrl(owner, recordId, "video/webm", 10);
        String key = storage.lastKey();
        storage.store(key, 10, "video/webm");
        mvc.perform(post(path(recordId) + "/complete").with(as(owner))).andExpect(status().isOk());

        mvc.perform(delete("/api/inning-records/" + recordId).with(as(owner))).andExpect(status().isNoContent());
        assertThat(storage.objects).doesNotContainKey(key);
        mvc.perform(get(path(recordId)).with(as(owner))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from inning_record_videos where inning_record_id = ? and deleted_at is null",
                Long.class, recordId)).isZero();
    }

    @Test
    void failedDeletionIsDurableAndBacksOffUntilStorageRecovers() throws Exception {
        var owner = user();
        long recordId = record(owner);
        long id = uploadUrl(owner, recordId, "video/mp4", 10).path("videoId").longValue();
        String key = storage.lastKey();
        storage.store(key, 10, "video/mp4");
        expire(id);
        storage.failedDeletes.add(key);
        mvc.perform(delete(path(recordId)).with(as(owner))).andExpect(status().isNoContent());
        assertThat(videos.cleanupRow(id).orElseThrow().video().deletedAt()).isNotNull();
        assertThat(cleanup.candidates()).contains(id);
        cleanup.cleanup(id);
        var failed = videos.cleanupRow(id).orElseThrow();
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.retryAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(failed.storageDeletedAt()).isNull();
        assertThat(cleanup.candidates()).doesNotContain(id);
        storage.failedDeletes.remove(key);
        sqlUpdate("update inning_record_videos set cleanup_retry_at = ? where id = ?", NOW, id);
        cleanup.cleanup(id);
        assertThat(storage.objects).doesNotContainKey(key);
        assertThat(videos.cleanupRow(id).orElseThrow().storageDeletedAt()).isEqualTo(NOW);
        int calls = storage.deleted.size();
        cleanup.cleanup(id);
        assertThat(storage.deleted).hasSize(calls);
    }

    @Test
    void cleanupProtectsUploadsInGraceAndReadyVideosEvenWhenGameLogIsHidden() throws Exception {
        var owner = user();
        long recordId = record(owner);
        long id = uploadUrl(owner, recordId, "video/webm", 10).path("videoId").longValue();
        String key = storage.lastKey();
        storage.store(key, 10, "video/webm; codecs=vp8");
        sqlUpdate("update inning_record_videos set upload_expires_at = ? where id = ?", NOW.minusSeconds(60), id);
        assertThat(cleanup.candidates()).doesNotContain(id);
        cleanup.cleanup(id);
        mvc.perform(post(path(recordId) + "/complete").with(as(owner))).andExpect(status().isOk());
        expire(id);
        sqlUpdate("update user_game_logs set deleted_at = ? where user_id = ?", NOW, owner.getId());
        cleanup.cleanup(id);
        assertThat(cleanup.candidates()).doesNotContain(id);
        assertThat(videos.findActive(recordId)).isPresent();
        assertThat(storage.objects).containsKey(key);
        sqlUpdate("update user_game_logs set deleted_at = null where user_id = ?", owner.getId());
        mvc.perform(get(path(recordId)).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    void abandonedUploadsExpireButFreshReplacementsSurvive() throws Exception {
        var owner = user();
        long recordId = record(owner);
        long id = uploadUrl(owner, recordId, "video/mp4", 10).path("videoId").longValue();
        String oldKey = storage.lastKey();
        storage.store(oldKey, 10, "video/mp4");
        expire(id);
        assertThat(cleanup.candidates()).contains(id);
        long replacement = uploadUrl(owner, recordId, "video/mp4", 10).path("videoId").longValue();
        String newKey = storage.lastKey();
        storage.store(newKey, 10, "video/mp4");
        cleanup.cleanup(id);
        cleanup.cleanup(replacement);
        assertThat(storage.objects).doesNotContainKey(oldKey).containsKey(newKey);
        assertThat(videos.findActive(recordId).orElseThrow().id()).isEqualTo(replacement);
        expire(replacement);
        cleanup.cleanup(replacement);
        assertThat(videos.findActive(recordId)).isEmpty();
        assertThat(storage.objects).doesNotContainKey(newKey);
    }

    @Test
    void expiredCompletionRejectsAndCommitsCleanup() throws Exception {
        var owner = user();
        long recordId = record(owner);
        long id = uploadUrl(owner, recordId, "video/mp4", 10).path("videoId").longValue();
        String key = storage.lastKey();
        storage.store(key, 10, "video/mp4");
        expire(id);
        mvc.perform(post(path(recordId) + "/complete").with(as(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VIDEO_UPLOAD_EXPIRED"));
        assertThat(videos.findActive(recordId)).isEmpty();
        assertThat(storage.objects).doesNotContainKey(key);
    }

    @Test
    void deletedUploadIsSweptAgainAfterItsPutUrlCanNoLongerRecreateIt() throws Exception {
        var owner = user();
        long recordId = record(owner);
        long id = uploadUrl(owner, recordId, "video/mp4", 10).path("videoId").longValue();
        String key = storage.lastKey();
        mvc.perform(delete(path(recordId)).with(as(owner))).andExpect(status().isNoContent());
        cleanup.cleanup(id);
        var early = videos.cleanupRow(id).orElseThrow();
        assertThat(early.storageDeletedAt()).isNull();
        assertThat(early.retryAt()).isEqualTo(early.video().uploadExpiresAt().plusSeconds(3600));
        // A client started a PUT before cancellation and it finishes after the first DELETE.
        storage.store(key, 10, "video/mp4");
        expire(id);
        sqlUpdate("update inning_record_videos set cleanup_retry_at = ? where id = ?", NOW, id);
        cleanup.cleanup(id);
        assertThat(storage.objects).doesNotContainKey(key);
        assertThat(videos.cleanupRow(id).orElseThrow().storageDeletedAt()).isNotNull();
    }

    @Test
    void workerRechecksAStaleCandidateAfterCompletionAndProtectsLegacyUploads() throws Exception {
        var owner = user();
        long recordId = record(owner);
        long id = uploadUrl(owner, recordId, "video/mp4", 10).path("videoId").longValue();
        String key = storage.lastKey();
        storage.store(key, 10, "video/mp4");
        sqlUpdate("update inning_record_videos set upload_expires_at = null where id = ?", id);
        assertThat(videos.findActive(recordId).orElseThrow().uploadExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(cleanup.candidates()).doesNotContain(id);
        expire(id);
        assertThat(cleanup.candidates()).contains(id);
        // Simulate completion committed while a previously selected candidate waits for locks.
        videos.markReady(id, NOW);
        cleanup.cleanup(id);
        assertThat(storage.objects).containsKey(key);
        assertThat(videos.findActive(recordId)).isPresent();
    }

    @Test
    void contentTypeMustMatchExactlyInsteadOfOnlySharingAPrefix() throws Exception {
        var owner = user();
        long recordId = record(owner);
        uploadUrl(owner, recordId, "video/mp4", 10);
        String key = storage.lastKey();
        storage.store(key, 10, "video/mp4-invalid");
        mvc.perform(post(path(recordId) + "/complete").with(as(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VIDEO_UPLOAD_MISMATCH"));
        assertThat(videos.findActive(recordId)).isEmpty();
    }

    @Test
    void withdrawalQueuesReadyAndHiddenVideosAndSurvivesStorageFailure() throws Exception {
        var owner = user();
        long visibleRecord = record(owner);
        long ready = uploadUrl(owner, visibleRecord, "video/mp4", 10).path("videoId").longValue();
        String readyKey = storage.lastKey();
        storage.store(readyKey, 10, "video/mp4");
        mvc.perform(post(path(visibleRecord) + "/complete").with(as(owner))).andExpect(status().isOk());
        long hiddenRecord = record(owner);
        long pending = uploadUrl(owner, hiddenRecord, "video/mp4", 10).path("videoId").longValue();
        String hiddenKey = storage.lastKey();
        storage.store(hiddenKey, 10, "video/mp4");
        sqlUpdate("update inning_records set deleted_at = ? where id = ?", NOW, hiddenRecord);
        sqlUpdate("update user_game_logs set deleted_at = ? where id = (select user_game_log_id from inning_records where id = ?)",
                NOW, hiddenRecord);
        expire(ready);
        expire(pending);
        storage.failedDeletes.add(readyKey);
        mvc.perform(delete("/api/users/me").with(as(owner))).andExpect(status().isNoContent());
        assertThat(videos.cleanupRow(ready).orElseThrow().video().deletedAt()).isEqualTo(NOW);
        assertThat(videos.cleanupRow(pending).orElseThrow().video().deletedAt()).isEqualTo(NOW);
        assertThat(cleanup.candidates()).contains(ready, pending);
        cleanup.cleanup(ready);
        cleanup.cleanup(pending);
        assertThat(videos.cleanupRow(ready).orElseThrow().attempts()).isEqualTo(1);
        assertThat(storage.objects).containsKey(readyKey).doesNotContainKey(hiddenKey);
        assertThat(users.findById(owner.getId()).orElseThrow().isDeleted()).isTrue();
        storage.failedDeletes.remove(readyKey);
        sqlUpdate("update inning_record_videos set cleanup_retry_at = ? where id = ?", NOW, ready);
        cleanup.cleanup(ready);
        assertThat(storage.objects).doesNotContainKey(readyKey);
    }

    @Test
    void workerAlsoRecoversVideosLeftByWithdrawalsBeforeThisRelease() throws Exception {
        var owner = user();
        long recordId = record(owner);
        long id = uploadUrl(owner, recordId, "video/mp4", 10).path("videoId").longValue();
        String key = storage.lastKey();
        storage.store(key, 10, "video/mp4");
        mvc.perform(post(path(recordId) + "/complete").with(as(owner))).andExpect(status().isOk());
        expire(id);
        sqlUpdate("update app_users set deleted_at = ? where id = ?", NOW, owner.getId());
        sqlUpdate("update user_game_logs set deleted_at = ? where user_id = ?", NOW, owner.getId());
        assertThat(videos.cleanupRow(id).orElseThrow().video().deletedAt()).isNull();
        assertThat(cleanup.candidates()).contains(id);
        cleanup.cleanup(id);
        assertThat(videos.cleanupRow(id).orElseThrow().storageDeletedAt()).isNotNull();
        assertThat(videos.findActive(recordId)).isEmpty();
        assertThat(storage.objects).doesNotContainKey(key);
    }

    @Test
    void rolledBackDeletionDoesNotRemoveTheObjectOrLeaveADeletionQueue() throws Exception {
        var owner = user();
        long recordId = record(owner);
        long id = uploadUrl(owner, recordId, "video/mp4", 10).path("videoId").longValue();
        String key = storage.lastKey();
        storage.store(key, 10, "video/mp4");
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            videoService.delete(owner.getId().toString(), recordId);
            throw new IllegalStateException("Rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(videos.findActive(recordId)).isPresent();
        assertThat(storage.objects).containsKey(key);
        assertThat(cleanup.candidates()).doesNotContain(id);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            accountDeletion.deleteCurrentUser(owner.getId().toString());
            throw new IllegalStateException("Rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(users.findById(owner.getId()).orElseThrow().isDeleted()).isFalse();
        assertThat(videos.findActive(recordId)).isPresent();
        assertThat(cleanup.candidates()).doesNotContain(id);
        assertThat(storage.objects).containsKey(key);
    }

    private void expire(long id) {
        sqlUpdate("update inning_record_videos set upload_expires_at = ? where id = ?", NOW.minusSeconds(3600), id);
    }

    private long record(User user) throws Exception {
        var game = game(TODAY, "FINISHED", 5, 3);
        // Reuse identified games across users instead of inventing duplicate schedules.
        String opponent = OPPONENTS.get(SEQUENCE.getAndIncrement() % OPPONENTS.size());
        game.put("awayTeam", Map.of("code", opponent));
        game.put("externalId", Map.of("kbo", "video-api-" + opponent));
        long gameId = ingest(game);
        viewing(user, gameId, team("LG"), "STADIUM");
        var body = Map.of("gameId", gameId, "clientRecordId", UUID.randomUUID().toString(), "inning", 1,
                "half", "TOP", "recordedAt", NOW.minusSeconds(30).toString(), "text", "영상 기록");
        var response = mvc.perform(post("/api/inning-records").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().isCreated()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString()).path("id").longValue();
    }

    private JsonNode uploadUrl(User user, long recordId, String type, long size) throws Exception {
        var response = mvc.perform(post(path(recordId) + "/upload-url").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content(body(type, size))).andExpect(status().isOk()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString());
    }

    private String recordStatus(User user, long recordId) throws Exception {
        var response = mvc.perform(get("/api/inning-records/" + recordId).with(as(user))).andExpect(status().isOk()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString()).path("videoStatus").stringValue();
    }

    private String body(String type, long size) {
        return mapper.writeValueAsString(Map.of("contentType", type, "contentLength", size));
    }

    private static String path(long recordId) { return "/api/inning-records/" + recordId + "/video"; }

    private void befriend(User from, User to) {
        var friendship = Friendship.request(from, to, NOW);
        friendship.accept(NOW);
        friendships.saveAndFlush(friendship);
    }
}
