package prepare.edt.orchestrator.adapter;

import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
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
 *   at the end: the Batch result
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

    @Override
    public BatchResult run(BatchInput input) {
        String batchId = Workflow.getInfo().getWorkflowId();
        if (input.progress() != null) {
            progress = input.progress();
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
            LOG.info("Batch {} continues as new at Page {}", batchId, progress.nextPage());
            Workflow.continueAsNew(new BatchInput(input.bucket(), input.key(), input.versionId(), progress));
        }
        BatchResult result = new BatchResult(batchId, progress.nextPage(), progress.devices(), progress.invalidRows(),
                progress.verdicts());
        LOG.info("Batch {} ended: {} devices, {} invalid rows, Verdicts {}", batchId, result.devices(),
                result.invalidRows(), result.verdicts());
        return result;
    }

    private void startPage(String batchId, BatchInput input) {
        int page = progress.nextPage();
        long offset = progress.nextOffset();
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

        Async.function(child::run, pageInput).handle((result, error) -> {
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
