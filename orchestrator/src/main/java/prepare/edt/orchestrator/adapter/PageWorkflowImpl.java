package prepare.edt.orchestrator.adapter;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import prepare.edt.orchestrator.adapter.FlowWorkflow.FlowInput;
import prepare.edt.orchestrator.adapter.ManifestReaderActivity.PageRows;
import prepare.edt.orchestrator.entity.ManifestRow;

/**
 * The Page workflow (model 010.010, step 11):
 *
 *   1. read its rows                    ManifestReaderActivity (S3 byte ranges)
 *   2. tell the Batch where the next Page starts   signal "PageRead"
 *   3. run one Flow per row, at most flowsAtATime at a time, starting the next as one ends
 *   4. return the Verdict counts
 */
public class PageWorkflowImpl implements PageWorkflow {

    private static final Logger LOG = Workflow.getLogger(PageWorkflowImpl.class);

    /** A Flow that failed (it should not: a Flow ends with a Verdict) is counted under this name. */
    static final String FLOW_FAILED = "FLOW_FAILED";

    private final ManifestReaderActivity reader = Workflow.newActivityStub(ManifestReaderActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(1))
                    // S3 errors are retried (default: without limit, with backoff); a file that is not a Manifest is not.
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setDoNotRetry(ManifestReaderActivityImpl.NOT_A_MANIFEST)
                            .build())
                    .build());

    private final Map<String, Integer> verdicts = new TreeMap<>();
    private int running;

    @Override
    public PageResult run(PageInput input) {
        PageRows rows = reader.readPage(new ManifestReaderActivity.Request(input.bucket(), input.key(),
                input.versionId(), input.offset(), input.rows(), input.firstRowNumber()));

        Workflow.getInfo().getParentWorkflowId().ifPresent(batchId ->
                Workflow.newExternalWorkflowStub(BatchWorkflow.class, batchId)
                        .pageRead(new BatchWorkflow.PageRead(input.page(), rows.nextOffset(), rows.endOfManifest())));

        for (ManifestRow row : rows.rows()) {
            Workflow.await(() -> running < input.flowsAtATime());
            startFlow(input.batchId(), row);
        }
        Workflow.await(() -> running == 0);

        LOG.info("Page {} ended: {} devices, Verdicts {}", input.page(), rows.rows().size(), verdicts);
        return new PageResult(rows.rows().size(), rows.invalidRows(), verdicts);
    }

    private void startFlow(String batchId, ManifestRow row) {
        FlowWorkflow flow = Workflow.newChildWorkflowStub(FlowWorkflow.class, ChildWorkflowOptions.newBuilder()
                .setWorkflowId(WorkflowIds.flow(batchId, row.rowNumber()))
                .build());
        running++;
        Async.function(flow::run, new FlowInput(batchId, row.rowNumber(), row.deviceId(), row.deviceType(), row.flow()))
                .handle((verdict, error) -> {
                    running--;
                    verdicts.merge(error == null ? verdict : FLOW_FAILED, 1, Integer::sum);
                    return null;
                });
    }
}
