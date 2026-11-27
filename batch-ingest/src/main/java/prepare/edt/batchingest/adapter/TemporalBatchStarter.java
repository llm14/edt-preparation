package prepare.edt.batchingest.adapter;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import prepare.edt.batchingest.entity.ManifestRef;
import prepare.edt.batchingest.infrastructure.InterfaceLog;
import prepare.edt.batchingest.infrastructure.LogFields;
import prepare.edt.batchingest.usecase.BatchStarter;

/**
 * The Batch Starter (model 100.010): starts the Batch workflow on the Orchestrator task queue.
 *
 * Untyped stub: Batch Ingest knows the workflow by its type name, not by a Java interface of the Orchestrator.
 *
 * Exactly once per Manifest version:
 *   - the workflow ID comes from bucket, key and version
 *   - while it runs, a second start with that ID fails with WorkflowExecutionAlreadyStarted
 *   - REJECT_DUPLICATE: also after it has ended (a late duplicate message must not run the Batch again)
 */
public final class TemporalBatchStarter implements BatchStarter {

    public static final String WORKFLOW_TYPE = "Batch";

    /** Model interface: Batch Starter to the Temporal Server (gRPC). */
    static final String TEMPORAL_INTERFACE = "edt.batchIngestReqGrpc";

    private final WorkflowClient client;
    private final String taskQueue;

    public TemporalBatchStarter(WorkflowClient client, String taskQueue) {
        this.client = client;
        this.taskQueue = taskQueue;
    }

    /**
     * Starts a new process: the Batch workflow, on the Orchestrator task queue, through the Temporal Server. This is
     * the only place that starts a Batch; the Orchestrator then runs it. Logs "Batch started" at INFO (or "Batch already
     * started" for a duplicate message, where no process is started).
     *
     * @return true if a new Batch was started, false if one already exists for this Manifest version
     */
    @Override
    public boolean start(ManifestRef manifest) {
        var stub = client.newUntypedWorkflowStub(WORKFLOW_TYPE, WorkflowOptions.newBuilder()
                .setWorkflowId(manifest.batchWorkflowId())
                .setTaskQueue(taskQueue)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
        long startedAt = System.nanoTime();
        boolean started;
        RuntimeException failure = null;
        try {
            stub.start(new BatchInput(manifest.bucket(), manifest.key(), manifest.versionId()));
            started = true;
        } catch (WorkflowExecutionAlreadyStarted e) {
            started = false; // not a failure: a duplicate message, and the Batch exists
        } catch (RuntimeException e) {
            started = false;
            failure = e;
        }
        long nanos = System.nanoTime() - startedAt;

        try (var ids = LogFields.of("edt.batch_id", manifest.batchWorkflowId(), "edt.temporal.task_queue", taskQueue);
                var call = InterfaceLog.outbound(TEMPORAL_INTERFACE, started || failure != null ? "batch-started" : "batch-already-started")
                        .duration(nanos)) {
            if (failure != null) {
                call.failure().log("Could not start the Batch");
                throw failure;
            }
            call.log(started ? "Batch started" : "Batch already started: duplicate message");
        }
        return started;
    }
}
