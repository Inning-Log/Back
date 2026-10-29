package com.inninglog.domain.mypage.image;

import com.inninglog.domain.video.config.VideoProperties;
import com.inninglog.domain.video.storage.VideoStorage.PresignedUrl;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Component
@ConditionalOnProperty(prefix = "app.videos", name = "enabled", havingValue = "true")
public class S3ProfileImageStorage implements ProfileImageStorage {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ProfileImageStorage(S3Client s3, S3Presigner presigner, VideoProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = properties.bucket();
    }

    @Override
    public PresignedUrl presignUpload(UUID id, String type, long size, Duration ttl) {
        try {
            var signed = presigner.presignPutObject(r -> r.signatureDuration(ttl)
                    .putObjectRequest(p -> p.bucket(bucket).key(staging(id)).contentType(type).contentLength(size)));
            Map<String, String> headers = new LinkedHashMap<>();
            signed.signedHeaders().forEach((name, values) -> {
                // Fetch owns Host and Content-Length; the browser supplies the Blob's actual length.
                if (!name.equalsIgnoreCase("host") && !name.equalsIgnoreCase("content-length"))
                    headers.put(name, String.join(",", values));
            });
            return new PresignedUrl(signed.url().toString(), "PUT", headers, signed.expiration());
        } catch (SdkException e) { throw unavailable(); }
    }

    @Override
    public byte[] readUpload(UUID id, String type, long size) {
        // Read at most the declared size + 1, even if a malicious uploader races the request.
        try (var body = s3.getObject(r -> r.bucket(bucket).key(staging(id)))) {
            if (body.response().contentLength() != size || !type.equalsIgnoreCase(body.response().contentType())) {
                body.abort();
                throw mismatch();
            }
            byte[] bytes = body.readNBytes(Math.toIntExact(size + 1));
            if (bytes.length != size) {
                body.abort();
                throw mismatch();
            }
            return bytes;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) throw new ProfileImageException(HttpStatus.CONFLICT,
                    "PROFILE_IMAGE_NOT_UPLOADED", "이미지 업로드를 완료한 뒤 다시 시도해 주세요.");
            throw unavailable();
        } catch (IOException | SdkException e) { throw unavailable(); }
    }

    @Override
    public void publish(UUID id, byte[] png) {
        try {
            // Clients never receive PUT access to this separate, sanitized object.
            s3.putObject(r -> r.bucket(bucket).key(published(id)).contentType("image/png")
                    .cacheControl("private, max-age=300"), RequestBody.fromBytes(png));
        } catch (SdkException e) { throw unavailable(); }
    }

    @Override
    public String downloadUrl(UUID id, Duration ttl) {
        try {
            return presigner.presignGetObject(r -> r.signatureDuration(ttl)
                    .getObjectRequest(g -> g.bucket(bucket).key(published(id)))).url().toString();
        } catch (SdkException e) { throw unavailable(); }
    }

    @Override
    public void deleteUpload(UUID id) {
        try { s3.deleteObject(r -> r.bucket(bucket).key(staging(id))); }
        catch (SdkException e) { throw unavailable(); }
    }

    @Override
    public void deletePublished(UUID id) {
        try { s3.deleteObject(r -> r.bucket(bucket).key(published(id))); }
        catch (SdkException e) { throw unavailable(); }
    }

    static String staging(UUID id) { return "profile-images/staging/" + id; }
    static String published(UUID id) { return "profile-images/images/" + id + ".png"; }
    private static ProfileImageException mismatch() {
        return new ProfileImageException(HttpStatus.CONFLICT, "PROFILE_IMAGE_UPLOAD_MISMATCH",
                "업로드한 이미지의 크기 또는 형식이 요청과 다릅니다.");
    }
    private static ProfileImageException unavailable() {
        return new ProfileImageException(HttpStatus.SERVICE_UNAVAILABLE, "PROFILE_IMAGE_STORAGE_UNAVAILABLE",
                "이미지 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
}
