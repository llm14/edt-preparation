package prepare.edt.batchingest.adapter;

import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import prepare.edt.batchingest.entity.ManifestRef;
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

    private final WorkflowClient client;
    private final String taskQueue;

    public TemporalBatchStarter(WorkflowClient client, String taskQueue) {
        this.client = client;
        this.taskQueue = taskQueue;
    }

    @Override
    public boolean start(ManifestRef manifest) {
        var stub = client.newUntypedWorkflowStub(WORKFLOW_TYPE, WorkflowOptions.newBuilder()
                .setWorkflowId(manifest.batchWorkflowId())
                .setTaskQueue(taskQueue)
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .build());
        try {
            stub.start(new BatchInput(manifest.bucket(), manifest.key(), manifest.versionId()));
            return true;
        } catch (WorkflowExecutionAlreadyStarted e) {
            return false;
        }
    }
}
