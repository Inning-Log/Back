package com.inninglog.domain.gamevideo.queue;

import com.inninglog.domain.gamevideo.config.GameVideoProperties;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * Owns its own SQS client instead of exposing a bean, so it never competes with the game snapshot consumer's client.
 * Credentials come from the default AWS chain (EC2 instance role in production).
 */
@Component
@ConditionalOnProperty(prefix = "app.game-videos", name = "enabled", havingValue = "true")
public class SqsGameVideoQueue implements GameVideoQueue, DisposableBean {
    private final SqsClient sqs;
    private final String jobQueueUrl;
    private final String resultQueueUrl;

    public SqsGameVideoQueue(GameVideoProperties properties) {
        this.jobQueueUrl = requireHttps(properties.jobQueueUrl());
        this.resultQueueUrl = requireHttps(properties.resultQueueUrl());
        this.sqs = SqsClient.builder().region(Region.of(properties.region()))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(15))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
    }

    @Override
    public void sendJob(String body) {
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(jobQueueUrl).messageBody(body).build());
    }

    @Override
    public List<Message> receiveResults(int max) {
        return sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(resultQueueUrl)
                        .maxNumberOfMessages(max).waitTimeSeconds(1).visibilityTimeout(60).build())
                .messages().stream().map(message -> new Message(message.messageId(), message.body(), message.receiptHandle()))
                .toList();
    }

    @Override
    public void acknowledge(Message message) {
        sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(resultQueueUrl).receiptHandle(message.receiptHandle()).build());
    }

    @Override
    public void destroy() { sqs.close(); }

    private static String requireHttps(String url) {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null)
            throw new IllegalArgumentException("A valid HTTPS SQS queue URL is required.");
        return url;
    }
}
