package com.inninglog.domain.game;

import static org.mockito.Mockito.*;

import com.inninglog.domain.game.ingestion.GameImportException;
import com.inninglog.domain.game.ingestion.GameSnapshotConsumer;
import com.inninglog.domain.game.ingestion.GameSnapshotImporter;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

class GameSnapshotConsumerTest {
    @Test
    void acknowledgesOnlyAfterSuccessfulTransactionalImportIncludingDuplicates() {
        SqsClient sqs = mock(SqsClient.class);
        GameSnapshotImporter importer = mock(GameSnapshotImporter.class);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().messageId("message").receiptHandle("receipt").body("snapshot").build()).build());
        when(importer.importMessage("snapshot")).thenReturn(new GameSnapshotImporter.ImportResult(true,1,"key"));
        new GameSnapshotConsumer(sqs,importer,"https://sqs.example.test/queue").poll();
        var order = inOrder(sqs,importer);
        order.verify(sqs).receiveMessage(any(ReceiveMessageRequest.class));
        order.verify(importer).importMessage("snapshot");
        order.verify(sqs).deleteMessage(argThat((DeleteMessageRequest request) -> request.receiptHandle().equals("receipt")));
    }

    @Test
    void failedImportRemainsUnacknowledgedForRetryOrDlq() {
        SqsClient sqs = mock(SqsClient.class);
        GameSnapshotImporter importer = mock(GameSnapshotImporter.class);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().messageId("message").receiptHandle("receipt").body("invalid").build()).build());
        when(importer.importMessage("invalid")).thenThrow(new GameImportException("INVALID_SNAPSHOT"));
        new GameSnapshotConsumer(sqs,importer,"https://sqs.example.test/queue").poll();
        verify(sqs,never()).deleteMessage(any(DeleteMessageRequest.class));
    }
}
