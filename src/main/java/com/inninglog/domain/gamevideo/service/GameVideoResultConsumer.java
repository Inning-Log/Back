package com.inninglog.domain.gamevideo.service;

import com.inninglog.domain.gamevideo.queue.GameVideoQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the worker's result queue, like the game snapshot consumer. */
@Component
@ConditionalOnProperty(prefix = "app.game-videos", name = "enabled", havingValue = "true")
public class GameVideoResultConsumer {
    private static final Logger log = LoggerFactory.getLogger(GameVideoResultConsumer.class);
    private final GameVideoQueue queue;
    private final GameVideoJobs jobs;

    public GameVideoResultConsumer(GameVideoQueue queue, GameVideoJobs jobs) {
        this.queue = queue;
        this.jobs = jobs;
    }

    @Scheduled(scheduler = "gameVideoScheduler", fixedDelayString = "${app.game-videos.poll-delay:2s}",
            initialDelayString = "${app.game-videos.initial-delay:5s}")
    public void poll() {
        try {
            for (var message : queue.receiveResults(10)) {
                try {
                    jobs.handleResult(message.body()); // returns after commit
                    queue.acknowledge(message);
                } catch (RuntimeException error) {
                    // Left on the queue for another attempt; the queue's DLQ catches poison messages.
                    log.warn("Game video result {} was not acknowledged ({})", message.id(), error.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException error) {
            log.warn("Game video result polling failed ({})", error.getClass().getSimpleName());
        }
    }
}
