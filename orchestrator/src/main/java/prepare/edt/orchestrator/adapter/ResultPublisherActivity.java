package prepare.edt.orchestrator.adapter;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Result Publisher Activity (model): publishes to Kafka each step of a device Flow, the result of each device Flow
 * and the result of each Batch, keyed by device. PLACEHOLDER event fields until DCC reporting says what it needs.
 *
 * The event ID is derived from the workflow and step, so a publish that Temporal retries carries the same ID.
 */
@ActivityInterface
public interface ResultPublisherActivity {

    /** The stub workflows use. Kafka down: retried by Temporal (default policy, with backoff) until it is back. */
    static ResultPublisherActivity stub() {
        return Workflow.newActivityStub(ResultPublisherActivity.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .build());
    }

    /** The workflow's current time (deterministic on replay), as an ISO-8601 instant. */
    static String now() {
        return Instant.ofEpochMilli(Workflow.currentTimeMillis()).toString();
    }

    @ActivityMethod(name = "PublishFlowStep")
    void publishFlowStep(FlowStep event);

    @ActivityMethod(name = "PublishDeviceResult")
    void publishDeviceResult(DeviceResult event);

    @ActivityMethod(name = "PublishBatchResult")
    void publishBatchResult(BatchResult event);

    /** One Service Request of a device Flow and how it ended. */
    record FlowStep(String eventId, String time, String batchId, String deviceId, long rowNumber, int step,
                    String srv, String requestId, String status, String alertCode, Map<String, String> facts) {
    }

    /** The Verdict of one device. */
    record DeviceResult(String eventId, String time, String batchId, String deviceId, long rowNumber, String flow,
                        String verdict) {
    }

    /** The result of one Batch. */
    record BatchResult(String eventId, String time, String batchId, int pages, int devices, int invalidRows,
                       Map<String, Integer> verdicts) {
    }
}
