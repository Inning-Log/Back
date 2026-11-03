package com.inninglog.domain.gamevideo.queue;

import java.util.List;

/** Job and result queues shared with the video worker. */
public interface GameVideoQueue {
    void sendJob(String body);

    /** Receives up to {@code max} results. Each must be acknowledged after it is handled. */
    List<Message> receiveResults(int max);

    void acknowledge(Message message);

    record Message(String id, String body, String receiptHandle) {}
}
