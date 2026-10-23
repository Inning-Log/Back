package com.inninglog.domain.video.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.videos", name = "enabled", havingValue = "true")
public class VideoCleanupSchedulingConfig {
    // Otherwise Spring may select the only custom scheduler as the default when SQS is disabled.
    @Bean(name = "taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    public ThreadPoolTaskScheduler backgroundTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("background-jobs-");
        return scheduler;
    }

    @Bean(name = "videoCleanupExecutor")
    public ThreadPoolTaskScheduler videoCleanupExecutor() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("video-cleanup-");
        return scheduler;
    }
}
