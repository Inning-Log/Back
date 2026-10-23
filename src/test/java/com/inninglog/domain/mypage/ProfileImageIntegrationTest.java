package com.inninglog.domain.mypage;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inninglog.domain.mypage.image.*;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.repository.UserRepository;
import com.inninglog.domain.video.storage.VideoStorage.PresignedUrl;
import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = {
        "spring.datasource.url=${PROFILE_TEST_JDBC_URL:jdbc:h2:mem:profile_images;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${PROFILE_TEST_DB_USER:sa}",
        "spring.datasource.password=${PROFILE_TEST_DB_PASSWORD:}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ProfileImageIntegrationTest.StorageConfig.class)
class ProfileImageIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeStorage storage;
    @Autowired ProfileImageService images;
    @Autowired ProfileImageRepository uploads;

    @Test
    void uploadedImageIsValidatedPublishedAndReturnedThroughExistingProfileContract() throws Exception {
        var user = user();
        byte[] input = png(2048, 512);
        var upload = request(user, "image/png", input.length);
        assertThat(upload.url()).startsWith("/api/profile-images/");
        storage.files.put(upload.id(), new File(input, "image/png"));
        complete(user, upload.id()).andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value(upload.url()));
        byte[] published = storage.published.get(upload.id()).clone();
        var decoded = ImageIO.read(new java.io.ByteArrayInputStream(published));
        assertThat(decoded.getWidth()).isEqualTo(1024);
        assertThat(decoded.getHeight()).isEqualTo(256);
        mvc.perform(get("/api/mypage").with(as(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value(upload.url()));
        mvc.perform(get(upload.url())).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://private-bucket.example/" + upload.id()))
                .andExpect(header().string("Cache-Control", "no-store"));
        // Reusing a still-valid staging PUT never changes the published image.
        storage.files.put(upload.id(), new File("not an image".getBytes(), "image/png"));
        expire(upload.id());
        complete(user, upload.id()).andExpect(status().isOk());
        assertThat(storage.published.get(upload.id())).isEqualTo(published);
    }

    @Test
    void existingPutCanFinalizeAndResetWhileReplacementRevokesPreviousImage() throws Exception {
        var user = user();
        var first = uploaded(user);
        putUrl(user, first.url()).andExpect(status().isOk());
        var second = uploaded(user);
        complete(user, second.id()).andExpect(status().isOk());
        mvc.perform(get(first.url())).andExpect(status().isNotFound());
        complete(user, first.id()).andExpect(status().isConflict());
        putUrl(user, null).andExpect(status().isOk()).andExpect(jsonPath("$.profileImageUrl").doesNotExist());
        mvc.perform(get(second.url())).andExpect(status().isNotFound());
        complete(user, second.id()).andExpect(status().isConflict());
    }

    @Test
    void rejectsOtherUsersAndReplacedOrExpiredUploadSessions() throws Exception {
        var owner = user();
        var other = user();
        var first = uploaded(owner);
        complete(other, first.id()).andExpect(status().isNotFound());
        putUrl(other, first.url()).andExpect(status().isNotFound());
        var second = uploaded(owner);
        complete(owner, first.id()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_IMAGE_UPLOAD_REPLACED"));
        expire(second.id());
        complete(owner, second.id()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_IMAGE_UPLOAD_EXPIRED"));
        mvc.perform(get(second.url())).andExpect(status().isNotFound());
        assertThat(storage.published).doesNotContainKeys(first.id(), second.id());
    }

    @Test
    void validatesAuthenticationContentTypeAndSizeBeforeIssuingUpload() throws Exception {
        mvc.perform(post("/api/mypage/profile-image/upload-url").contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/png\",\"contentLength\":10}")).andExpect(status().isUnauthorized());
        var user = user();
        for (String body : List.of(
                "{\"contentType\":\"image/svg+xml\",\"contentLength\":10}",
                "{\"contentType\":\"image/webp\",\"contentLength\":10}",
                "{\"contentType\":\"image/png\",\"contentLength\":0}",
                "{\"contentType\":\"image/png\"}", "{\"contentLength\":10}")) {
            mvc.perform(post("/api/mypage/profile-image/upload-url").with(as(user))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/mypage/profile-image/upload-url").with(as(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentType\":\"image/png\",\"contentLength\":10485761}"))
                .andExpect(status().is(413));
        request(user, "image/png", 10 * 1024 * 1024);
        mvc.perform(get("/api/profile-images/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingCorruptedMislabeledAndOversizedDimensionsWithoutChangingProfile() throws Exception {
        var user = user();
        var missing = request(user, "image/png", 10);
        complete(user, missing.id()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_IMAGE_NOT_UPLOADED"));
        storage.files.put(missing.id(), new File(new byte[11], "image/png"));
        complete(user, missing.id()).andExpect(status().isConflict());
        storage.files.put(missing.id(), new File(new byte[10], "image/png"));
        complete(user, missing.id()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROFILE_IMAGE"));
        byte[] bytes = png(2, 2);
        var mislabeled = request(user, "image/jpeg", bytes.length);
        storage.files.put(mislabeled.id(), new File(bytes, "image/jpeg"));
        complete(user, mislabeled.id()).andExpect(status().isBadRequest());
        byte[] tooWide = png(8193, 1);
        var huge = request(user, "image/png", tooWide.length);
        storage.files.put(huge.id(), new File(tooWide, "image/png"));
        complete(user, huge.id()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROFILE_IMAGE_DIMENSIONS_EXCEEDED"));
        assertThat(users.findById(user.getId()).orElseThrow().getProfileImageUrl()).isNull();
    }

    @Test
    void cleanupWaitsForExpiryAndRetriesFailuresWithoutDeletingTheCurrentImage() throws Exception {
        var user = user();
        var active = uploaded(user);
        complete(user, active.id()).andExpect(status().isOk());
        images.cleanup(active.id());
        assertThat(storage.files).containsKey(active.id());
        expire(active.id());
        images.cleanup(active.id());
        assertThat(storage.files).doesNotContainKey(active.id());
        assertThat(storage.published).containsKey(active.id());
        assertThat(images.cleanupCandidates()).doesNotContain(active.id());
        putUrl(user, null).andExpect(status().isOk());
        storage.failDeletes.add(active.id());
        assertThatThrownBy(() -> images.cleanup(active.id())).isInstanceOf(ProfileImageException.class);
        assertThat(uploads.find(active.id())).isPresent();
        storage.failDeletes.remove(active.id());
        images.cleanup(active.id());
        assertThat(storage.published).doesNotContainKey(active.id());
        assertThat(uploads.find(active.id())).isEmpty();
        // An abandoned or superseded upload is also collected after the URL expires.
        var abandoned = uploaded(user);
        expire(abandoned.id());
        images.cleanup(abandoned.id());
        assertThat(storage.files).doesNotContainKey(abandoned.id());
        assertThat(uploads.find(abandoned.id())).isEmpty();
    }

    @Test
    void deletedAccountsImmediatelyLoseDisplayAndTheirObjectsAreCleaned() throws Exception {
        var user = user();
        var upload = uploaded(user);
        complete(user, upload.id()).andExpect(status().isOk());
        jdbc.update("update app_users set deleted_at = ?, profile_image_url = null where id = ?",
                Timestamp.from(Instant.now()), user.getId());
        mvc.perform(get(upload.url())).andExpect(status().isNotFound());
        complete(user, upload.id()).andExpect(status().isConflict());
        expire(upload.id());
        assertThat(images.cleanupCandidates()).contains(upload.id());
        images.cleanup(upload.id());
        assertThat(storage.published).doesNotContainKey(upload.id());
    }

    @Test
    void simultaneousCompletionPublishesOnceAndReturnsTheSameImage() throws Exception {
        var user = user();
        var upload = uploaded(user);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<String> action = () -> {
                start.await();
                return images.complete(user.getId().toString(), upload.id()).profileImageUrl();
            };
            var first = executor.submit(action);
            var second = executor.submit(action);
            start.countDown();
            assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(upload.url());
            assertThat(second.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(upload.url());
        }
        assertThat(storage.publishCalls.get(upload.id()).get()).isEqualTo(1);
    }

    @Test
    void failedPublicationLeavesOldProfileAndAllowsRetry() throws Exception {
        var user = user();
        var old = uploaded(user);
        complete(user, old.id()).andExpect(status().isOk());
        var next = uploaded(user);
        storage.failPublish.add(next.id());
        complete(user, next.id()).andExpect(status().isServiceUnavailable());
        assertThat(users.findById(user.getId()).orElseThrow().getProfileImageUrl()).isEqualTo(old.url());
        assertThat(uploads.find(next.id()).orElseThrow().status()).isEqualTo("PENDING");
        storage.failPublish.remove(next.id());
        complete(user, next.id()).andExpect(status().isOk());
    }

    private User user() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", null));
    }
    private RequestPostProcessor as(User user) {
        return jwt().jwt(j -> j.subject(user.getId().toString()));
    }
    private record Upload(UUID id, String url) {}
    private Upload request(User user, String type, long size) throws Exception {
        var body = mvc.perform(post("/api/mypage/profile-image/upload-url").with(as(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentType\":\"" + type + "\",\"contentLength\":" + size + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.method").value("PUT"))
                .andReturn().getResponse().getContentAsString();
        return new Upload(UUID.fromString(JsonPath.read(body, "$.uploadId")), JsonPath.read(body, "$.profileImageUrl"));
    }
    private Upload uploaded(User user) throws Exception {
        byte[] bytes = png(2, 2);
        var upload = request(user, "image/png", bytes.length);
        storage.files.put(upload.id(), new File(bytes, "image/png"));
        return upload;
    }
    private org.springframework.test.web.servlet.ResultActions complete(User user, UUID id) throws Exception {
        return mvc.perform(post("/api/mypage/profile-image/" + id + "/complete").with(as(user)));
    }
    private org.springframework.test.web.servlet.ResultActions putUrl(User user, String url) throws Exception {
        return mvc.perform(put("/api/mypage/profile-image").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileImageUrl\":" + (url == null ? "null" : "\"" + url + "\"") + "}"));
    }
    private void expire(UUID id) {
        jdbc.update("update profile_image_uploads set expires_at = ? where id = ?",
                Timestamp.from(Instant.now().minusSeconds(600)), id);
    }
    static byte[] png(int w, int h) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
    record File(byte[] bytes, String type) {}
    static class FakeStorage implements ProfileImageStorage {
        final Map<UUID, File> files = new ConcurrentHashMap<>();
        final Map<UUID, byte[]> published = new ConcurrentHashMap<>();
        final Set<UUID> failDeletes = ConcurrentHashMap.newKeySet();
        final Set<UUID> failPublish = ConcurrentHashMap.newKeySet();
        final Map<UUID, java.util.concurrent.atomic.AtomicInteger> publishCalls = new ConcurrentHashMap<>();
        public PresignedUrl presignUpload(UUID id, String type, long size, Duration ttl) {
            return new PresignedUrl("https://upload.example/" + id, "PUT",
                    Map.of("content-type", type), Instant.now().plus(ttl));
        }
        public byte[] readUpload(UUID id, String type, long size) {
            var file = files.get(id);
            if (file == null) throw new ProfileImageException(HttpStatus.CONFLICT, "PROFILE_IMAGE_NOT_UPLOADED", "missing");
            if (file.bytes().length != size || !file.type().equals(type))
                throw new ProfileImageException(HttpStatus.CONFLICT, "PROFILE_IMAGE_UPLOAD_MISMATCH", "mismatch");
            return file.bytes();
        }
        public void publish(UUID id, byte[] png) {
            if (failPublish.contains(id)) throw new ProfileImageException(HttpStatus.SERVICE_UNAVAILABLE, "FAILED", "failed");
            publishCalls.computeIfAbsent(id, key -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            published.put(id, png);
        }
        public String downloadUrl(UUID id, Duration ttl) { return "https://private-bucket.example/" + id; }
        public void deleteUpload(UUID id) { checkDelete(id); files.remove(id); }
        public void deletePublished(UUID id) { checkDelete(id); published.remove(id); }
        private void checkDelete(UUID id) {
            if (failDeletes.contains(id)) throw new ProfileImageException(HttpStatus.SERVICE_UNAVAILABLE, "FAILED", "failed");
        }
    }
    @TestConfiguration
    static class StorageConfig {
        @Bean @Primary FakeStorage fakeProfileImageStorage() { return new FakeStorage(); }
    }
}

