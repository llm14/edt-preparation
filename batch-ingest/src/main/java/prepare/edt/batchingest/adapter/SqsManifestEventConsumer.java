package prepare.edt.batchingest.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prepare.edt.batchingest.adapter.ObjectCreatedEvent.UnreadableEventException;
import prepare.edt.batchingest.entity.ManifestRef;
import prepare.edt.batchingest.infrastructure.Interaction;
import prepare.edt.batchingest.infrastructure.LogFields;
import prepare.edt.batchingest.usecase.IngestManifest;
import prepare.edt.batchingest.usecase.IngestManifest.Outcome;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * The Manifest Event Consumer (model 100.010): "long-polls the Manifest Events queue, hands each message on and
 * deletes it once the Batch has started or the Manifest has been rejected. Logs the receipt and outcome of each
 * Manifest."
 *
 * SQS delivers "at least once": a received message is only hidden for the queue's visibility timeout. If it is not
 * deleted by then, it comes back. That is the retry: when S3, KMS or Temporal cannot be reached, or the message
 * cannot be read, the message is left alone. In production, after a number of receipts DCC's dead-letter queue
 * takes it.
 */
public final class SqsManifestEventConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(SqsManifestEventConsumer.class);
    private static final Logger SECURITY = LoggerFactory.getLogger("edt.security");

    /** The longest long poll SQS allows: an empty queue costs one request every 20 seconds. */
    static final int WAIT_SECONDS = 20;

    /** Model interface: Manifest Event Consumer to the Manifest Events queue (receive and delete). */
    static final String SQS_INTERFACE = "edt.batchIngestReqSqs";

    private final SqsClient sqs;
    private final String queueName;
    private final IngestManifest ingestManifest;
    private String queueUrl;

    public SqsManifestEventConsumer(SqsClient sqs, String queueName, IngestManifest ingestManifest) {
        this.sqs = sqs;
        this.queueName = queueName;
        this.ingestManifest = ingestManifest;
    }

    /** One long poll: waits up to 20 s for messages, handles each. Returns how many it received. */
    public int pollOnce() {
        if (queueUrl == null) {
            queueUrl = sqs.getQueueUrl(r -> r.queueName(queueName)).queueUrl();
        }
        long startedAt = System.nanoTime();
        var messages = sqs.receiveMessage(r -> r
                .queueUrl(queueUrl)
                .waitTimeSeconds(WAIT_SECONDS)
                .maxNumberOfMessages(10)).messages();
        long receiveNanos = System.nanoTime() - startedAt;
        messages.forEach(message -> handle(message, receiveNanos));
        return messages.size();
    }

    void handle(Message message) {
        handle(message, 0);
    }

    /** {@code receiveNanos}: how long the receive call that returned this message took. */
    void handle(Message message, long receiveNanos) {
        ManifestRef manifest;
        try {
            manifest = ObjectCreatedEvent.parse(message.body());
        } catch (UnreadableEventException e) {
            try (var fields = LogFields.of("event.action", "manifest-event-unreadable",
                    "event.outcome", "failure", "error.message", e.getMessage(),
                    "edt.sqs_message_id", message.messageId())) {
                LOG.error("Unreadable Manifest event: left on the queue (it ends on the dead-letter queue)");
            }
            return;
        }

        try (var fields = LogFields.of("edt.manifest.bucket", manifest.bucket(), "edt.manifest.key", manifest.key(),
                "edt.manifest.version_id", manifest.versionId(), "edt.sqs_message_id", message.messageId())) {
            try (var received = Interaction.outbound(SQS_INTERFACE, "manifest-received").duration(receiveNanos)) {
                received.log("Manifest received");
            }

            Outcome outcome;
            try {
                outcome = ingestManifest.ingest(manifest);
            } catch (RuntimeException e) {
                // A technical failure: the exception is passed, so the line has error.type, error.message and the stack.
                try (var failed = LogFields.of("event.action", "manifest-ingest-failed", "event.outcome", "failure")) {
                    LOG.error("Could not ingest the Manifest: left on the queue, it will come back", e);
                }
                return;
            }

            // Started and AlreadyStarted are logged by the Batch Starter, at the call to Temporal.
            if (outcome instanceof Outcome.Rejected rejected) {
                try (var security = LogFields.of("event.kind", "alert", "event.category", "intrusion_detection",
                        "event.action", "manifest-signature-invalid", "event.outcome", "failure",
                        "error.message", rejected.reason())) {
                    SECURITY.warn("Manifest rejected: its DCC signature is not valid");
                }
            }
            try (var deleted = Interaction.outbound(SQS_INTERFACE, "sqs-message-deleted")) {
                try {
                    sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
                } catch (RuntimeException e) {
                    deleted.failure().log("Could not delete the message from the Manifest Events queue");
                    throw e;
                }
                deleted.log("Message deleted from the Manifest Events queue");
            }
        }
    }
}
