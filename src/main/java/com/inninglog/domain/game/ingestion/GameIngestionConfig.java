package com.inninglog.domain.game.ingestion;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.games.sqs", name = "enabled", havingValue = "true")
public class GameIngestionConfig {
    @Bean
    public SqsClient gameSqsClient(@Value("${app.games.sqs.region}") String region,
                                   @Value("${app.games.sqs.queue-url}") String queueUrl) {
        URI uri = URI.create(queueUrl);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null)
            throw new IllegalArgumentException("A valid HTTPS game snapshot queue URL is required.");
        return SqsClient.builder().region(Region.of(region))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(15))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
    }

    @Bean(name = "gameSnapshotScheduler")
    public ThreadPoolTaskScheduler gameSnapshotScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("game-snapshots-");
        return scheduler;
    }
}
