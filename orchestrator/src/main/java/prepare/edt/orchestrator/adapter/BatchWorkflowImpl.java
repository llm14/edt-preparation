package prepare.edt.orchestrator.adapter;

import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
import prepare.edt.orchestrator.infrastructure.InterfaceLog;
import prepare.edt.orchestrator.infrastructure.LogFields;
import prepare.edt.orchestrator.adapter.PageWorkflow.PageInput;

/**
 * The Batch workflow (model 010.010, step 11):
 *
 *   while there are rows:
 *     start Page n at the known offset          (at most pagesAtATime Pages running)
 *     Page n reads its rows and signals "PageRead": the offset of Page n+1 is now known
 *     when a Page ends, add its Verdict counts
 *   every pagesPerRun Pages: wait for the running Pages, then continue as new with the offset and the counts,
 *   so the history stays small for a Manifest of any size
 *   at the end: the Batch result, published to Kafka
 *
 * Pages run one after the other only for the short time it takes to read rows; their Flows run in parallel.
 * Cancelling the Batch (Temporal UI) cancels its Pages, and they cancel their Flows: child workflows are cancelled
 * with their parent.
 */
public class BatchWorkflowImpl implements BatchWorkflow {

    private static final Logger LOG = Workflow.getLogger(BatchWorkflowImpl.class);

    private final BatchSettings settings;

    private BatchProgress progress = BatchProgress.start();
    private boolean nextOffsetKnown = true;   // the first Page of a run starts at the carried offset
    private boolean endReached;
    private int waitingForPage = -1;
    private int running;
    private String failure;
    /** Bumped on every event (signal, Page ended): the main loop waits for it to change. */
    private int changes;

    public BatchWorkflowImpl(BatchSettings settings) {
        this.settings = settings;
    }

    /**
     * Runs the Batch. This method does not start the Batch: Batch Ingest started it (TemporalBatchStarter, through the
     * Temporal Server) and the Orchestrator worker is now running it. It does start one new process for each Page
     * (child workflow, see {@link #startPage}).
     */
    @Override
    public BatchResult processManifest(BatchInput input) {
        String batchId = Workflow.getInfo().getWorkflowId();
        if (input.progress() != null) {
            progress = input.progress();
        } else {
            // First run: the start Batch Ingest asked for (it is the one that started this workflow; this line only
            // logs that the Orchestrator received it). Not repeated after a continue-as-new.
            try (var call = InterfaceLog.inbound("edt.orchestratorReqGrpc", "batch-start-received")
                    .with("edt.batch_id", batchId)) {
                call.log(Workflow.getLogger(InterfaceLog.LOGGER), "Batch start received");
            }
        }
        int startedThisRun = 0;

        while (true) {
            while (failure == null && !endReached && nextOffsetKnown
                    && running < settings.pagesAtATime() && startedThisRun < settings.pagesPerRun()) {
                startPage(batchId, input);
                startedThisRun++;
            }
            boolean noMorePagesThisRun = failure != null || endReached || startedThisRun >= settings.pagesPerRun();
            if (noMorePagesThisRun && running == 0) {
                break;
            }
            int seen = changes;
            Workflow.await(() -> changes != seen);
        }

        if (failure != null) {
            throw ApplicationFailure.newNonRetryableFailure(failure, "BatchFailed");
        }
        if (!endReached) {
            try (var fields = LogFields.of("event.action", "batch-continued-as-new", "edt.batch_id", batchId,
                    "edt.page", progress.nextPage())) {
                LOG.info("Batch continues as new");
            }
            Workflow.continueAsNew(new BatchInput(input.bucket(), input.key(), input.versionId(), progress));
        }
        BatchResult result = new BatchResult(batchId, progress.nextPage(), progress.devices(), progress.invalidRows(),
                progress.verdicts());
        try (var fields = LogFields.of("event.action", "batch-ended", "edt.batch_id", batchId,
                "edt.devices", result.devices(), "edt.manifest.invalid_rows", result.invalidRows(),
                "edt.verdicts", result.verdicts())) {
            LOG.info("Batch ended");
        }
        // Kafka (model 010.010, step 12): the Batch result. Its event ID is the Batch ID.
        ResultPublisherActivity.stub().publishBatchResult(new ResultPublisherActivity.BatchResult(batchId,
                ResultPublisherActivity.now(), batchId, result.pages(), result.devices(), result.invalidRows(),
                result.verdicts()));
        return result;
    }

    /**
     * Starts a new process: a Page child workflow (PageWorkflow), with its own workflow ID and its own history. It does
     * not wait for the Page to end: the Page runs on its own, and its result is added to the progress when it ends.
     * Logs "Page started" at INFO.
     */
    private void startPage(String batchId, BatchInput input) {
        int page = progress.nextPage();
        long offset = progress.nextOffset();
        try (var fields = LogFields.of("event.action", "page-started", "edt.batch_id", batchId, "edt.page", page,
                "edt.manifest.offset", offset)) {
            LOG.info("Page started");
        }
        var pageInput = new PageInput(batchId, input.bucket(), input.key(), input.versionId(), page, offset,
                (long) page * settings.rowsPerPage() + 1, settings.rowsPerPage(), settings.flowsPerPage());
        PageWorkflow child = Workflow.newChildWorkflowStub(PageWorkflow.class, ChildWorkflowOptions.newBuilder()
                .setWorkflowId(WorkflowIds.page(batchId, page))
                .build());

        progress = progress.pageStarted();
        nextOffsetKnown = false;
        waitingForPage = page;
        running++;
        changes++;

        Async.function(child::processPage, pageInput).handle((result, error) -> {
            running--;
            changes++;
            if (error != null) {
                failure = "Page " + page + " failed: " + error.getMessage();
            } else {
                progress = progress.add(result);
            }
            return null;
        });
    }

    @Override
    public void pageRead(PageRead pageRead) {
        if (pageRead.page() != waitingForPage) {
            return;   // a late or repeated signal: the offset is already known
        }
        progress = progress.nextPageAt(pageRead.nextOffset());
        endReached = pageRead.endOfManifest();
        nextOffsetKnown = true;
        waitingForPage = -1;
        changes++;
    }

    @Override
    public BatchProgress progress() {
        return progress;
    }

    /** The Batch's settings, from configuration. Passed in by the worker's factory. */
    public record BatchSettings(int rowsPerPage, int pagesAtATime, int pagesPerRun, int flowsPerPage) {
    }
}
