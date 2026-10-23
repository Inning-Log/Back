package com.inninglog.domain.video.config;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VideoProperties.class)
public class VideoStorageConfig {

    // Credentials come from the default AWS chain (EC2 instance role in production, AWS profile locally).
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.videos", name = "enabled", havingValue = "true")
    public S3Client videoS3Client(VideoProperties properties) {
        return S3Client.builder().region(Region.of(properties.region()))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(15))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.videos", name = "enabled", havingValue = "true")
    public S3Presigner videoS3Presigner(VideoProperties properties) {
        return S3Presigner.builder().region(Region.of(properties.region())).build();
    }
}
