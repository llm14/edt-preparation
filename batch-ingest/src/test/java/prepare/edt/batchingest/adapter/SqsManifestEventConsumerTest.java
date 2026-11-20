package prepare.edt.batchingest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void the_message_received_and_the_message_deleted_are_two_interaction_lines() {
        try (LogCapture log = new LogCapture()) {
            consumer(m -> Verdict.signedByDcc(), true).pollOnce();

            var lines = log.lines("edt.interaction");
            assertEquals(List.of("manifest-received", "sqs-message-deleted"),
                    lines.stream().map(l -> l.fields().get("event.action")).toList());
            for (var line : lines) {
                assertEquals("edt.batchIngestReqSqs", line.fields().get("edt.interface"));
                assertEquals("success", line.fields().get("event.outcome"));
                assertEquals("m1", line.fields().get("edt.sqs_message_id"));
            }
            var received = lines.get(0).fields();
            assertEquals("inbound", received.get("network.direction"));
            assertFalse(received.containsKey("event.duration"), "the long poll's wait is not the work of the message");
            var deleted = lines.get(1).fields();
            assertEquals("outbound", deleted.get("network.direction"));
            assertTrue(Long.parseLong(deleted.get("event.duration")) >= 0);
            assertEquals("edt-manifests", lines.getFirst().fields().get("edt.manifest.bucket"));
        }
    }

    @Test
    void a_poll_with_no_message_writes_no_interaction_line() {
        var empty = new SqsClient() {
            @Override
            public GetQueueUrlResponse getQueueUrl(GetQueueUrlRequest request) {
                return GetQueueUrlResponse.builder().queueUrl("http://floci:4566/000000000000/q").build();
            }

            @Override
            public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
                return ReceiveMessageResponse.builder().build();
            }

            @Override
            public String serviceName() {
                return "sqs";
            }

            @Override
            public void close() {
            }
        };
        try (LogCapture log = new LogCapture()) {
            int received = new SqsManifestEventConsumer(empty, "q",
                    new IngestManifest(m -> Verdict.signedByDcc(), m -> true)).pollOnce();

            assertEquals(0, received);
            assertEquals(List.of(), log.lines("edt.interaction"));
        }
    }

    @Test
    void a_technical_failure_passes_the_exception_and_sets_no_error_fields_by_hand() {
        try (LogCapture log = new LogCapture()) {
            consumer(m -> {
                throw new IllegalStateException("KMS unreachable");
            }, true).pollOnce();

            var line = log.lines(SqsManifestEventConsumer.class).stream()
                    .filter(l -> "manifest-ingest-failed".equals(l.fields().get("event.action"))).findFirst().orElseThrow();
            assertEquals("KMS unreachable", line.thrown().getMessage());
            assertFalse(line.fields().containsKey("error.message"));
            assertFalse(line.fields().containsKey("error.type"));
        }
    }

    @Test
    void an_unreadable_message_keeps_error_message_as_a_field_and_has_no_exception() {
        try (LogCapture log = new LogCapture()) {
            consumer(m -> Verdict.signedByDcc(), true).handle(message("not json"));

            var line = log.lines(SqsManifestEventConsumer.class).stream()
                    .filter(l -> "manifest-event-unreadable".equals(l.fields().get("event.action"))).findFirst().orElseThrow();
            assertTrue(line.fields().containsKey("error.message"));
            assertEquals(null, line.thrown());
        }
    }

    @Test
    void an_invalid_manifest_signature_keeps_error_message_as_a_field_and_has_no_exception() {
        try (LogCapture log = new LogCapture()) {
            consumer(m -> Verdict.invalid("signature does not match the Manifest"), true).pollOnce();

            var line = log.lines("edt.security").get(0);
            assertEquals("manifest-signature-invalid", line.fields().get("event.action"));
            assertEquals("signature does not match the Manifest", line.fields().get("error.message"));
            assertEquals(null, line.thrown());
        }
    }

    @Test
    void the_batch_lines_are_written_by_the_starter_not_the_consumer() {
        try (LogCapture log = new LogCapture()) {
            consumer(m -> Verdict.signedByDcc(), true).pollOnce();

            assertEquals(List.of(), log.lines(SqsManifestEventConsumer.class).stream()
                    .filter(l -> l.message().startsWith("Batch ")).toList());
            assertFalse(log.lines("edt.interaction").stream()
                    .anyMatch(l -> l.fields().containsKey("edt.batch_workflow_id")));
        }
    }
}
