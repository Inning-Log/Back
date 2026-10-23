package com.inninglog.domain.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inninglog.domain.video.config.VideoCleanupSchedulingConfig;
import com.inninglog.domain.video.service.VideoCleanupScheduler;
import com.inninglog.domain.video.service.VideoCleanupService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class VideoCleanupSchedulingTest {
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {}

    @Test
    void slowStorageCleanupCannotBlockOtherScheduledJobs() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var unrelatedJob = new CountDownLatch(1);
        var threadName = new AtomicReference<String>();
        var service = mock(VideoCleanupService.class);
        when(service.candidates()).thenAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return List.of();
        });
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "app.videos.enabled", "true", "app.videos.cleanup-initial-delay", "PT0S")));
            context.register(Scheduling.class, VideoCleanupSchedulingConfig.class, VideoCleanupScheduler.class);
            context.registerBean(VideoCleanupService.class, () -> service);
            context.refresh();
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                context.getBean("taskScheduler", ThreadPoolTaskScheduler.class).execute(unrelatedJob::countDown);
                assertThat(unrelatedJob.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(threadName.get()).startsWith("video-cleanup-");
            } finally {
                release.countDown();
            }
        }
    }
}
