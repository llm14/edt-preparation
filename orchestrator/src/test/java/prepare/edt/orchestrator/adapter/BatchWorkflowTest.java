package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import prepare.edt.orchestrator.adapter.BatchWorkflow.BatchResult;
import prepare.edt.orchestrator.usecase.ReadManifestPage;
import prepare.edt.orchestrator.usecase.ReadManifestPageTest;

/**
 * The Batch and Page workflows on an in-memory Temporal server, with the real Manifest Reader over an in-memory
 * Manifest and a stand-in Flow (its Verdict comes from the device ID's last group).
 */
@Timeout(30)
class BatchWorkflowTest {

    static final String QUEUE = "orchestrator";
    static final String BATCH_ID = "batch-edt-manifests/sample.csv/v1";

    /** Flows that ran, by row; and the most Flows running at once. Static: Temporal creates the workflow objects. */
    static final Map<Long, String> FLOWS = new ConcurrentHashMap<>();
    static final List<String> FLOW_IDS = new CopyOnWriteArrayList<>();
    static final AtomicInteger RUNNING = new AtomicInteger();
    static final AtomicInteger MAX_RUNNING = new AtomicInteger();

    public static class StandInFlow implements FlowWorkflow {
        @Override
        public String run(FlowInput input) {
            MAX_RUNNING.accumulateAndGet(RUNNING.incrementAndGet(), Math::max);
            Workflow.sleep(Duration.ofSeconds(10));   // so that Flows overlap
            RUNNING.decrementAndGet();
            FLOWS.put(input.rowNumber(), input.deviceId());
            FLOW_IDS.add(Workflow.getInfo().getWorkflowId());
            return input.deviceId().endsWith("A3") ? "NO_RESPONSE" : "RESPONDS";
        }
    }

    TestWorkflowEnvironment env;
    WorkflowClient client;

    void start(String csv, BatchWorkflowImpl.BatchSettings settings) {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationFactory(BatchWorkflow.class, () -> new BatchWorkflowImpl(settings));
        worker.registerWorkflowImplementationTypes(PageWorkflowImpl.class, StandInFlow.class);
        worker.registerActivitiesImplementations(new ManifestReaderActivityImpl(
                new ReadManifestPage(new ReadManifestPageTest.InMemory(csv), 16)));
        env.start();
        client = env.getWorkflowClient();
    }

    @BeforeEach
    void reset() {
        FLOWS.clear();
        FLOW_IDS.clear();
        RUNNING.set(0);
        MAX_RUNNING.set(0);
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    /** Starts it as Batch Ingest does: by type name, with {bucket, key, versionId} only. */
    WorkflowExecution startBatch() {
        return client.newUntypedWorkflowStub("Batch", WorkflowOptions.newBuilder()
                        .setWorkflowId(BATCH_ID).setTaskQueue(QUEUE).build())
                .start(new BatchIngestInput("edt-manifests", "sample.csv", "v1"));
    }

    /** Batch Ingest's own copy of the input: three fields, no progress. */
    record BatchIngestInput(String bucket, String key, String versionId) {
    }

    BatchResult result() {
        return client.newUntypedWorkflowStub(BATCH_ID).getResult(BatchResult.class);
    }

    @Test
    void every_device_runs_once_and_the_verdicts_are_counted() {
        start(ReadManifestPageTest.CSV, new BatchWorkflowImpl.BatchSettings(2, 2, 10, 2));
        startBatch();

        BatchResult result = result();

        assertEquals(new BatchResult(BATCH_ID, 3, 5, 0, Map.of("NO_RESPONSE", 1, "RESPONDS", 4)), result);
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), FLOWS.keySet().stream().sorted().toList());
        assertTrue(FLOW_IDS.contains(BATCH_ID + "/row-3"));
        assertEquals(4, MAX_RUNNING.get(), "2 Pages at a time x 2 Flows per Page");
    }

    @Test
    void it_continues_as_new_and_carries_the_offset_and_the_counts() {
        start(ReadManifestPageTest.CSV, new BatchWorkflowImpl.BatchSettings(2, 2, 1, 2));
        String firstRun = startBatch().getRunId();

        BatchResult result = result();

        assertEquals(new BatchResult(BATCH_ID, 3, 5, 0, Map.of("NO_RESPONSE", 1, "RESPONDS", 4)), result);
        assertNotEquals(firstRun, latestRunId(), "one Page per run: three runs");
        assertEquals(5, FLOWS.size());
    }

    @Test
    void invalid_rows_are_counted() {
        String csv = ReadManifestPageTest.CSV.replace("00-DB-12-34-56-78-90-A1,ESME", "bad,ESME");
        start(csv, new BatchWorkflowImpl.BatchSettings(2, 2, 10, 2));
        startBatch();

        BatchResult result = result();

        assertEquals(1, result.invalidRows());
        assertEquals(4, result.devices());
        assertEquals(List.of(1L, 3L, 4L, 5L), FLOWS.keySet().stream().sorted().toList(), "row numbers stay in place");
    }

    @Test
    void a_file_that_is_not_a_manifest_fails_the_batch() {
        start("id;type\n00-DB-12-34-56-78-90-A0;ESME\n", new BatchWorkflowImpl.BatchSettings(2, 2, 10, 2));
        startBatch();

        assertThrows(WorkflowFailedException.class, this::result);
        assertEquals(0, FLOWS.size());
    }

    String latestRunId() {
        return env.getWorkflowServiceStubs().blockingStub().describeWorkflowExecution(
                DescribeWorkflowExecutionRequest.newBuilder()
                        .setNamespace(env.getNamespace())
                        .setExecution(WorkflowExecution.newBuilder().setWorkflowId(BATCH_ID))
                        .build()).getWorkflowExecutionInfo().getExecution().getRunId();
    }
}
