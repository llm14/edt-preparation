package prepare.edt.orchestrator.adapter;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Map;
import java.util.TreeMap;

/**
 * One Batch per Manifest (model): runs its Pages, continues as new every so many Pages, ends with the Batch result.
 *
 * Started by Batch Ingest by type name "Batch" with {bucket, key, versionId}; Batch Ingest keeps its own copy of
 * that input. The workflow ID (from bucket, key and version) is the Batch ID.
 */
@WorkflowInterface
public interface BatchWorkflow {

    @WorkflowMethod(name = "Batch")
    BatchResult processManifest(BatchInput input);

    /** From a Page, once it has read its rows: where the next Page starts. [INFERRED] the model does not draw it. */
    @SignalMethod(name = "PageRead")
    void pageRead(PageRead pageRead);

    /** Progress and Verdict counts so far, shown in the Temporal UI (Queries tab). */
    @QueryMethod(name = "progress")
    BatchProgress progress();

    /**
     * @param progress null when Batch Ingest starts it; the carried state when the Batch continues as new
     */
    record BatchInput(String bucket, String key, String versionId, BatchProgress progress) {
    }

    record PageRead(int page, long nextOffset, boolean endOfManifest) {
    }

    /**
     * What the Batch carries from one run to the next (continue as new) and shows as progress.
     *
     * @param nextPage   number of the next Page to start (0 is the first)
     * @param nextOffset byte where that Page starts
     * @param devices    devices whose Flow has ended
     * @param invalidRows rows skipped as invalid
     * @param verdicts   devices per Verdict
     */
    record BatchProgress(int nextPage, long nextOffset, int devices, int invalidRows, Map<String, Integer> verdicts) {

        public BatchProgress {
            verdicts = new TreeMap<>(verdicts);
        }

        static BatchProgress start() {
            return new BatchProgress(0, 0, 0, 0, Map.of());
        }

        BatchProgress pageStarted() {
            return new BatchProgress(nextPage + 1, nextOffset, devices, invalidRows, verdicts);
        }

        BatchProgress nextPageAt(long offset) {
            return new BatchProgress(nextPage, offset, devices, invalidRows, verdicts);
        }

        BatchProgress add(PageWorkflow.PageResult page) {
            Map<String, Integer> sum = new TreeMap<>(verdicts);
            page.verdicts().forEach((verdict, count) -> sum.merge(verdict, count, Integer::sum));
            return new BatchProgress(nextPage, nextOffset, devices + page.devices(), invalidRows + page.invalidRows(), sum);
        }
    }

    /** The Batch result (also published to Kafka). */
    record BatchResult(String batchId, int pages, int devices, int invalidRows, Map<String, Integer> verdicts) {
    }
}
