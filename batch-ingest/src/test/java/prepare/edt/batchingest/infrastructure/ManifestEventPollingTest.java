package prepare.edt.batchingest.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import prepare.edt.batchingest.adapter.LogCapture;
import prepare.edt.batchingest.adapter.SqsManifestEventConsumer;
import prepare.edt.batchingest.usecase.IngestManifest;
import prepare.edt.batchingest.usecase.ManifestVerifier.Verdict;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

/** When the queue cannot be polled, the line carries the exception (a technical failure), not hand-set error fields. */
class ManifestEventPollingTest {

    @Test
    void a_failed_poll_is_logged_with_its_exception_and_without_error_fields_set_by_hand() throws Exception {
        var unreachable = new SqsClient() {
            @Override
            public GetQueueUrlResponse getQueueUrl(GetQueueUrlRequest request) {
                return GetQueueUrlResponse.builder().queueUrl("http://floci:4566/000000000000/q").build();
            }

            @Override
            public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
                throw new IllegalStateException("queue unreachable");
            }

            @Override
            public String serviceName() {
                return "sqs";
            }

            @Override
            public void close() {
            }
        };
        var polling = new ManifestEventPolling(new SqsManifestEventConsumer(unreachable, "q",
                new IngestManifest(m -> Verdict.signedByDcc(), m -> true)));

        try (LogCapture log = new LogCapture()) {
            polling.onStart(null);
            var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (log.lines(ManifestEventPolling.class).stream()
                    .noneMatch(l -> "manifest-poll-failed".equals(l.fields().get("event.action")))
                    && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            polling.onStop(null);

            var line = log.lines(ManifestEventPolling.class).stream()
                    .filter(l -> "manifest-poll-failed".equals(l.fields().get("event.action"))).findFirst().orElseThrow();
            assertNotNull(line.thrown());
            assertEquals("queue unreachable", line.thrown().getMessage());
            assertFalse(line.fields().containsKey("error.message"));
            assertFalse(line.fields().containsKey("error.type"));
        }
    }
}
