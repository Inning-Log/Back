package com.inninglog.domain.game.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@Component
@ConditionalOnProperty(prefix = "app.games.sqs", name = "enabled", havingValue = "true")
public class GameSnapshotConsumer {
    private static final Logger log = LoggerFactory.getLogger(GameSnapshotConsumer.class);
    private final SqsClient sqs;
    private final GameSnapshotImporter importer;
    private final String queueUrl;
    public GameSnapshotConsumer(SqsClient sqs, GameSnapshotImporter importer,
                                @Value("${app.games.sqs.queue-url}") String queueUrl) {
        this.sqs = sqs; this.importer = importer; this.queueUrl = queueUrl;
    }

    @Scheduled(scheduler = "gameSnapshotScheduler", fixedDelayString = "${app.games.sqs.poll-delay:2s}", initialDelayString = "${app.games.sqs.initial-delay:5s}")
    public void poll() {
        try {
            var response = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl)
                    .maxNumberOfMessages(1).waitTimeSeconds(1).visibilityTimeout(120).build());
            for (var message : response.messages()) {
                try {
                    importer.importMessage(message.body()); // returns only after DB commit
                    sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(queueUrl).receiptHandle(message.receiptHandle()).build());
                } catch (RuntimeException error) {
                    // Leave unacknowledged for retry / the queue's configured DLQ. Do not log payloads.
                    log.warn("Game snapshot {} was not acknowledged ({})", message.messageId(), error.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException error) {
            log.warn("Game snapshot polling failed ({})", error.getClass().getSimpleName());
        }
    }
}
