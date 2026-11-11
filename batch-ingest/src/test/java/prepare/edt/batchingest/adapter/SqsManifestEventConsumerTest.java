package prepare.edt.batchingest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import prepare.edt.batchingest.usecase.IngestManifest;
import prepare.edt.batchingest.usecase.ManifestVerifier;
import prepare.edt.batchingest.usecase.ManifestVerifier.Verdict;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/** When is a message deleted? Only once the Batch has started (or had started) or the Manifest was rejected. */
class SqsManifestEventConsumerTest {

    final List<String> deleted = new ArrayList<>();
    final List<String> started = new ArrayList<>();
    ReceiveMessageRequest lastReceive;

    final SqsClient sqs = new SqsClient() {
        @Override
        public GetQueueUrlResponse getQueueUrl(GetQueueUrlRequest request) {
            return GetQueueUrlResponse.builder().queueUrl("http://floci:4566/000000000000/" + request.queueName())
                    .build();
        }

        @Override
        public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
            lastReceive = request;
            return ReceiveMessageResponse.builder().messages(message(ObjectCreatedEventTest.EVENT)).build();
        }

        @Override
        public DeleteMessageResponse deleteMessage(DeleteMessageRequest request) {
            deleted.add(request.receiptHandle());
            return DeleteMessageResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return "sqs";
        }

        @Override
        public void close() {
        }
    };

    SqsManifestEventConsumer consumer(ManifestVerifier verifier, boolean firstStart) {
        return new SqsManifestEventConsumer(sqs, "edt-manifest-events", new IngestManifest(verifier, manifest -> {
            started.add(manifest.batchWorkflowId());
            return firstStart;
        }));
    }

    static Message message(String body) {
        return Message.builder().messageId("m1").receiptHandle("receipt-1").body(body).build();
    }

    @Test
    void a_long_poll_then_the_batch_starts_and_the_message_is_deleted() {
        int received = consumer(m -> Verdict.signedByDcc(), true).pollOnce();

        assertEquals(1, received);
        assertEquals(20, lastReceive.waitTimeSeconds(), "long poll");
        assertEquals(List.of("batch-edt-manifests/sample.csv/v1"), started);
        assertEquals(List.of("receipt-1"), deleted);
    }

    @Test
    void a_duplicate_message_is_deleted_too() {
        consumer(m -> Verdict.signedByDcc(), false).pollOnce();

        assertEquals(List.of("receipt-1"), deleted);
    }

    @Test
    void a_rejected_manifest_is_deleted_without_a_batch() {
        consumer(m -> Verdict.invalid("signature does not match the Manifest"), true).pollOnce();

        assertEquals(List.of(), started);
        assertEquals(List.of("receipt-1"), deleted);
    }

    @Test
    void when_kms_s3_or_temporal_fails_the_message_stays_and_comes_back() {
        consumer(m -> {
            throw new IllegalStateException("KMS unreachable");
        }, true).pollOnce();

        assertEquals(List.of(), deleted);
    }

    @Test
    void an_unreadable_message_stays_for_the_dead_letter_queue() {
        consumer(m -> Verdict.signedByDcc(), true).handle(message("not json"));

        assertEquals(List.of(), started);
        assertEquals(List.of(), deleted);
    }
}
