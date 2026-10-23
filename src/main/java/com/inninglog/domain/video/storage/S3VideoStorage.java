package com.inninglog.domain.video.storage;

import com.inninglog.domain.video.config.VideoProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Component
@ConditionalOnProperty(prefix = "app.videos", name = "enabled", havingValue = "true")
public class S3VideoStorage implements VideoStorage {
    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3VideoStorage(S3Client s3, S3Presigner presigner, VideoProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = properties.bucket();
    }

    @Override
    public PresignedUrl presignUpload(String key, String contentType, long contentLength, Duration ttl) {
        var presigned = presigner.presignPutObject(request -> request.signatureDuration(ttl)
                .putObjectRequest(put -> put.bucket(bucket).key(key).contentType(contentType).contentLength(contentLength)));
        return new PresignedUrl(presigned.url().toString(), "PUT", clientHeaders(presigned.signedHeaders()), presigned.expiration());
    }

    @Override
    public PresignedUrl presignDownload(String key, Duration ttl) {
        var presigned = presigner.presignGetObject(request -> request.signatureDuration(ttl)
                .getObjectRequest(get -> get.bucket(bucket).key(key)));
        return new PresignedUrl(presigned.url().toString(), "GET", Map.of(), presigned.expiration());
    }

    @Override
    public Optional<StoredObject> head(String key) {
        try {
            var head = s3.headObject(request -> request.bucket(bucket).key(key));
            return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
        } catch (NoSuchKeyException missing) {
            return Optional.empty();
        } catch (S3Exception error) {
            if (error.statusCode() == 404) return Optional.empty();
            throw error;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(request -> request.bucket(bucket).key(key));
    }

    // The browser sets Host itself; every other signed header must be sent exactly as signed.
    private static Map<String, String> clientHeaders(Map<String, List<String>> signed) {
        Map<String, String> headers = new LinkedHashMap<>();
        signed.forEach((name, values) -> {
            if (!"host".equalsIgnoreCase(name)) headers.put(name, String.join(",", values));
        });
        return headers;
    }
}
