package com.inninglog.domain.mypage.image;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.inninglog.domain.video.config.VideoProperties;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3ProfileImageStorageTest {
    private final S3Client s3 = mock(S3Client.class);
    private final VideoProperties properties =
            new VideoProperties(true, "private-profile-test", "ap-northeast-2", null, null, null, null, null);

    private S3Presigner presigner() {
        return S3Presigner.builder().region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
                .build();
    }

    @Test
    void uploadSignatureBindsContentTypeAndSizeButDoesNotAskFetchForForbiddenHeaders() {
        try (var signer = presigner()) {
            var store = new S3ProfileImageStorage(s3, signer, properties);
            UUID id = UUID.randomUUID();
            var signed = store.presignUpload(id, "image/png", 123, Duration.ofMinutes(5));
            assertThat(signed.url()).contains("/profile-images/staging/" + id)
                    .contains("content-length").contains("content-type");
            assertThat(signed.headers()).containsEntry("content-type", "image/png");
            assertThat(signed.headers().keySet()).noneMatch(k -> k.equalsIgnoreCase("host") || k.equalsIgnoreCase("content-length"));
            assertThat(store.downloadUrl(id, Duration.ofMinutes(5))).contains("/profile-images/images/" + id + ".png");
            verifyNoInteractions(s3);
        }
    }

    @Test
    void readsOnlyDeclaredSizeAndRejectsMetadataOrBodyMismatch() {
        var store = new S3ProfileImageStorage(s3, mock(S3Presigner.class), properties);
        stubObject(4, "image/png", new byte[]{1, 2, 3, 4});
        assertThat(store.readUpload(UUID.randomUUID(), "image/png", 4)).hasSize(4);
        stubObject(4, "image/jpeg", new byte[]{1, 2, 3, 4});
        assertThatThrownBy(() -> store.readUpload(UUID.randomUUID(), "image/png", 4))
                .isInstanceOf(ProfileImageException.class)
                .extracting(e -> ((ProfileImageException) e).code()).isEqualTo("PROFILE_IMAGE_UPLOAD_MISMATCH");
        stubObject(4, "image/png", new byte[]{1, 2, 3, 4, 5});
        assertThatThrownBy(() -> store.readUpload(UUID.randomUUID(), "image/png", 4))
                .isInstanceOf(ProfileImageException.class);
    }

    @Test
    void translatesMissingObjectsAndStorageFailuresWithoutExposingAwsDetails() {
        var store = new S3ProfileImageStorage(s3, mock(S3Presigner.class), properties);
        when(s3.getObject(org.mockito.ArgumentMatchers.<Consumer<GetObjectRequest.Builder>>any()))
                .thenThrow(S3Exception.builder().statusCode(404).message("internal details").build());
        assertThatThrownBy(() -> store.readUpload(UUID.randomUUID(), "image/png", 4))
                .isInstanceOf(ProfileImageException.class)
                .extracting(e -> ((ProfileImageException) e).code()).isEqualTo("PROFILE_IMAGE_NOT_UPLOADED");
        doThrow(S3Exception.builder().statusCode(403).message("internal details").build())
                .when(s3).getObject(org.mockito.ArgumentMatchers.<Consumer<GetObjectRequest.Builder>>any());
        assertThatThrownBy(() -> store.readUpload(UUID.randomUUID(), "image/png", 4))
                .isInstanceOf(ProfileImageException.class)
                .hasMessageNotContaining("internal details");
    }

    private void stubObject(long length, String type, byte[] bytes) {
        when(s3.getObject(org.mockito.ArgumentMatchers.<Consumer<GetObjectRequest.Builder>>any()))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().contentLength(length).contentType(type).build(),
                        AbortableInputStream.create(new ByteArrayInputStream(bytes))));
    }
}
