package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inninglog.domain.friendship.entity.Friendship;
import com.inninglog.domain.friendship.repository.FriendshipRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.video.storage.VideoStorage;
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

    @TestConfiguration
    static class StorageConfig {
        @Bean @Primary FakeVideoStorage fakeVideoStorage() { return new FakeVideoStorage(); }
    }

    /** In-memory stand-in for S3. Tests simulate the client's PUT with {@link #store}. */
    static class FakeVideoStorage implements VideoStorage {
        final Map<String, StoredObject> objects = new ConcurrentHashMap<>();
        final List<String> presigned = Collections.synchronizedList(new ArrayList<>());

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
        public void delete(String key) { objects.remove(key); }

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

    private long record(User user) throws Exception {
        var game = game(TODAY, "FINISHED", 5, 3);
        // A distinct opponent per record keeps the committed schedule rows unique.
        game.put("awayTeam", Map.of("code", OPPONENTS.get(SEQUENCE.getAndIncrement() % OPPONENTS.size())));
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
