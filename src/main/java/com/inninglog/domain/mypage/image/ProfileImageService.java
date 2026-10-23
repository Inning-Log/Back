package com.inninglog.domain.mypage.image;

import com.inninglog.domain.mypage.dto.MyPageResponse;
import com.inninglog.domain.mypage.dto.ProfileImageUploadRequest;
import com.inninglog.domain.mypage.dto.ProfileImageUploadResponse;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.exception.AccountDeletedException;
import com.inninglog.domain.user.exception.UserNotFoundException;
import com.inninglog.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileImageService {
    public static final String IMAGE_PATH = "/api/profile-images/";
    private final ProfileImageRepository uploads;
    private final UserRepository users;
    private final ProfileImageStorage storage;
    private final ProfileImageProcessor processor;
    private final ProfileImageProperties properties;
    private final Clock clock;

    public ProfileImageService(ProfileImageRepository uploads, UserRepository users, ProfileImageStorage storage,
            ProfileImageProcessor processor, ProfileImageProperties properties, Clock clock) {
        this.uploads = uploads;
        this.users = users;
        this.storage = storage;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ProfileImageUploadResponse requestUpload(String subject, ProfileImageUploadRequest request) {
        User user = lock(subject);
        String type = request.contentType().trim().toLowerCase(Locale.ROOT);
        if (!List.of("image/jpeg", "image/png").contains(type))
            throw error(HttpStatus.BAD_REQUEST, "UNSUPPORTED_PROFILE_IMAGE_TYPE", "JPEG 또는 PNG 이미지만 업로드할 수 있습니다.");
        if (request.contentLength() > properties.maxSize().toBytes())
            throw error(HttpStatus.CONTENT_TOO_LARGE, "PROFILE_IMAGE_TOO_LARGE",
                    "프로필 이미지는 최대 " + properties.maxSize().toMegabytes() + "MB입니다.");
        UUID id = UUID.randomUUID();
        var signed = storage.presignUpload(id, type, request.contentLength(), properties.uploadUrlTtl());
        uploads.retirePending(user.getId());
        uploads.insert(id, user.getId(), type, request.contentLength(), clock.instant(), signed.expiresAt());
        return new ProfileImageUploadResponse(id, signed.url(), signed.method(), signed.headers(),
                signed.expiresAt(), imageUrl(id));
    }

    @Transactional
    public MyPageResponse complete(String subject, UUID id) {
        User user = lock(subject);
        apply(user, imageUrl(id));
        return MyPageResponse.from(user);
    }

    /** Caller holds the user row lock, shared with reset, replacement and account deletion. */
    public void apply(User user, String url) {
        if (url == null || !url.startsWith(IMAGE_PATH)) {
            uploads.retireAll(user.getId());
            user.updateProfileImage(url);
            return;
        }
        UUID id;
        try { id = UUID.fromString(url.substring(IMAGE_PATH.length())); }
        catch (IllegalArgumentException e) { throw missing(); }
        // Only the canonical path returned by this service is a managed image reference.
        if (!imageUrl(id).equals(url)) throw missing();
        var upload = uploads.find(id).filter(u -> u.userId() == user.getId()).orElseThrow(ProfileImageService::missing);
        if (upload.status().equals("READY") && url.equals(user.getProfileImageUrl())) return;
        if (!upload.status().equals("PENDING"))
            throw error(HttpStatus.CONFLICT, "PROFILE_IMAGE_UPLOAD_REPLACED", "새 업로드 URL을 발급받아 주세요.");
        if (!clock.instant().isBefore(upload.expiresAt()))
            throw error(HttpStatus.CONFLICT, "PROFILE_IMAGE_UPLOAD_EXPIRED", "업로드 요청이 만료됐습니다. 새 URL을 발급받아 주세요.");
        byte[] original = storage.readUpload(id, upload.contentType(), upload.size());
        byte[] sanitized = processor.sanitize(original, upload.contentType());
        storage.publish(id, sanitized);
        uploads.retireOthers(user.getId(), id);
        uploads.ready(id);
        user.updateProfileImage(url);
    }

    @Transactional(readOnly = true)
    public String download(UUID id) {
        var upload = uploads.find(id).filter(u -> u.status().equals("READY")).orElseThrow(ProfileImageService::missing);
        var user = users.findByIdAndDeletedAtIsNull(upload.userId()).orElseThrow(ProfileImageService::missing);
        if (!imageUrl(id).equals(user.getProfileImageUrl())) throw missing();
        return storage.downloadUrl(id, properties.downloadUrlTtl());
    }

    public List<UUID> cleanupCandidates() {
        return uploads.cleanupCandidates(clock.instant().minus(Duration.ofMinutes(1)));
    }

    @Transactional
    public void cleanup(UUID id) {
        var candidate = uploads.find(id).orElse(null);
        if (candidate == null) return;
        // Each cleanup is its own transaction; a failed S3 delete is retried next time.
        var user = users.findByIdForUpdate(candidate.userId()).orElseThrow(UserNotFoundException::new);
        var upload = uploads.find(id).orElse(null);
        if (upload == null || !upload.expiresAt().plus(Duration.ofMinutes(1)).isBefore(clock.instant())) return;
        boolean current = upload.status().equals("READY") && !user.isDeleted()
                && imageUrl(id).equals(user.getProfileImageUrl());
        if (!upload.stagingDeleted()) {
            // Wait until PUT has expired so its reuse cannot recreate an abandoned object.
            storage.deleteUpload(id);
            uploads.stagingDeleted(id);
        }
        if (!current) {
            storage.deletePublished(id);
            uploads.delete(id);
        }
    }

    private User lock(String subject) {
        try {
            var user = users.findByIdForUpdate(Long.valueOf(subject)).orElseThrow(UserNotFoundException::new);
            if (user.isDeleted()) throw new AccountDeletedException();
            return user;
        } catch (NumberFormatException e) { throw new UserNotFoundException(); }
    }
    public static String imageUrl(UUID id) { return IMAGE_PATH + id; }
    private static ProfileImageException missing() {
        return error(HttpStatus.NOT_FOUND, "PROFILE_IMAGE_NOT_FOUND", "프로필 이미지 업로드를 찾을 수 없습니다.");
    }
    private static ProfileImageException error(HttpStatus status, String code, String message) {
        return new ProfileImageException(status, code, message);
    }
}

