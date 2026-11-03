package com.inninglog.domain.gamevideo.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GameVideoProperties.class)
public class GameVideoConfig {

    @Bean(name = "gameVideoScheduler")
    @ConditionalOnProperty(prefix = "app.game-videos", name = "enabled", havingValue = "true")
    public ThreadPoolTaskScheduler gameVideoScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("game-videos-");
        return scheduler;
    }
}
