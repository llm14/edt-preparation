package prepare.edt.orchestrator.adapter;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Map;
import java.util.TreeMap;

/** One Page of rows (model): reads its rows, runs their device Flows, returns its Verdict counts. */
@WorkflowInterface
public interface PageWorkflow {

    @WorkflowMethod(name = "Page")
    PageResult run(PageInput input);

    /**
     * @param page           Page number (0 is the first)
     * @param offset         byte where the Page starts
     * @param firstRowNumber number of its first row
     */
    record PageInput(String batchId, String bucket, String key, String versionId, int page, long offset,
                     long firstRowNumber, int rows, int flowsAtATime) {
    }

    record PageResult(int devices, int invalidRows, Map<String, Integer> verdicts) {

        public PageResult {
            verdicts = new TreeMap<>(verdicts);
        }
    }
}
