package prepare.edt.batchingest.adapter;

import org.jboss.logging.Logger;
import prepare.edt.batchingest.adapter.ObjectCreatedEvent.UnreadableEventException;
import prepare.edt.batchingest.entity.ManifestRef;
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

    private static final Logger LOG = Logger.getLogger(SqsManifestEventConsumer.class);
    private static final Logger SECURITY = Logger.getLogger("edt.security");

    /** The longest long poll SQS allows: an empty queue costs one request every 20 seconds. */
    static final int WAIT_SECONDS = 20;

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
        var messages = sqs.receiveMessage(r -> r
                .queueUrl(queueUrl)
                .waitTimeSeconds(WAIT_SECONDS)
                .maxNumberOfMessages(10)).messages();
        messages.forEach(this::handle);
        return messages.size();
    }

    void handle(Message message) {
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
            try (var received = LogFields.of("event.action", "manifest-received")) {
                LOG.info("Manifest received");
            }

            Outcome outcome;
            try {
                outcome = ingestManifest.ingest(manifest);
            } catch (RuntimeException e) {
                try (var failed = LogFields.of("event.action", "manifest-ingest-failed", "event.outcome", "failure",
                        "error.type", e.getClass().getName(), "error.message", String.valueOf(e.getMessage()))) {
                    LOG.error("Could not ingest the Manifest: left on the queue, it will come back");
                }
                return;
            }

            switch (outcome) {
                case Outcome.Started started -> logBatch("batch-started", "Batch started", manifest);
                case Outcome.AlreadyStarted already ->
                        logBatch("batch-already-started", "Batch already started: duplicate message", manifest);
                case Outcome.Rejected rejected -> {
                    try (var security = LogFields.of("event.kind", "alert", "event.category", "intrusion_detection",
                            "event.action", "manifest-signature-invalid", "event.outcome", "failure",
                            "error.message", rejected.reason())) {
                        SECURITY.warn("Manifest rejected: its DCC signature is not valid");
                    }
                }
            }
            sqs.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        }
    }

    private static void logBatch(String action, String text, ManifestRef manifest) {
        try (var fields = LogFields.of("event.action", action, "edt.batch_workflow_id", manifest.batchWorkflowId())) {
            LOG.info(text);
        }
    }
}
