package com.inninglog.domain.mypage.image;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import com.inninglog.domain.video.storage.VideoStorage.PresignedUrl;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProfileImageProperties.class)
public class ProfileImageStorageConfig {
    @Bean
    @ConditionalOnProperty(prefix = "app.videos", name = "enabled", havingValue = "false", matchIfMissing = true)
    ProfileImageStorage disabledProfileImageStorage() {
        return new ProfileImageStorage() {
            private ProfileImageException disabled() {
                return new ProfileImageException(HttpStatus.SERVICE_UNAVAILABLE, "PROFILE_IMAGE_STORAGE_DISABLED",
                        "프로필 이미지 저장소가 설정되지 않았습니다.");
            }
            public PresignedUrl presignUpload(UUID id, String type, long size, Duration ttl) { throw disabled(); }
            public byte[] readUpload(UUID id, String type, long size) { throw disabled(); }
            public void publish(UUID id, byte[] png) { throw disabled(); }
            public String downloadUrl(UUID id, Duration ttl) { throw disabled(); }
            public void deleteUpload(UUID id) { throw disabled(); }
            public void deletePublished(UUID id) { throw disabled(); }
        };
    }
}

