package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import prepare.edt.orchestrator.adapter.BatchWorkflow.BatchResult;
import prepare.edt.orchestrator.usecase.ReadManifestPage;
import prepare.edt.orchestrator.usecase.ReadManifestPageTest;

/**
 * The Batch and Page workflow log lines: their data is in fields and the message text is constant
 * (openspec/specs/structured-logging). Runs the real workflows on an in-memory Temporal server with a stand-in Flow.
 */
@Timeout(30)
class BatchLogFieldsTest {

    static final String QUEUE = "orchestrator";
    static final String BATCH_ID = "batch-edt-manifests/sample.csv/v1";

    public static class StandInFlow implements FlowWorkflow {
        @Override
        public String diagnoseDevice(FlowInput input) {
            Workflow.sleep(Duration.ofSeconds(1));
            return input.deviceId().endsWith("A3") ? "NO_RESPONSE" : "RESPONDS";
        }
    }

    TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        env.close();
    }

    BatchResult run(BatchWorkflowImpl.BatchSettings settings) {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationFactory(BatchWorkflow.class, () -> new BatchWorkflowImpl(settings));
        worker.registerWorkflowImplementationTypes(PageWorkflowImpl.class, StandInFlow.class);
        worker.registerActivitiesImplementations(new ManifestReaderActivityImpl(
                new ReadManifestPage(new ReadManifestPageTest.InMemory(ReadManifestPageTest.CSV), 16)),
                new RecordingResultPublisher());
        env.start();
        WorkflowClient client = env.getWorkflowClient();
        client.newUntypedWorkflowStub("Batch", WorkflowOptions.newBuilder()
                        .setWorkflowId(BATCH_ID).setTaskQueue(QUEUE).build())
                .start(new BatchWorkflowTest.BatchIngestInput("edt-manifests", "sample.csv", "v1"));
        return client.newUntypedWorkflowStub(BATCH_ID).getResult(BatchResult.class);
    }

    @Test
    void the_batch_and_page_ended_lines_carry_their_data_as_fields() {
        try (LogCapture log = new LogCapture()) {
            run(new BatchWorkflowImpl.BatchSettings(2, 2, 10, 2));

            var batch = log.lines(BatchWorkflowImpl.class, "Batch ended");
            assertEquals(1, batch.size(), "the message text is constant, and the line is written once");
            var fields = batch.get(0).fields();
            assertEquals("batch-ended", fields.get("event.action"));
            assertEquals(BATCH_ID, fields.get("edt.batch_id"));
            assertEquals("5", fields.get("edt.devices"));
            assertEquals("0", fields.get("edt.manifest.invalid_rows"));
            assertTrue(fields.get("edt.verdicts").contains("RESPONDS=4"));
            assertTrue(fields.get("edt.verdicts").contains("NO_RESPONSE=1"));

            var pages = log.lines(PageWorkflowImpl.class, "Page ended");
            assertEquals(3, pages.size(), "5 devices in Pages of 2 (numbered from 0)");
            assertEquals(List.of("0", "1", "2"),
                    pages.stream().map(l -> l.fields().get("edt.page")).sorted().toList());
            assertTrue(pages.stream().allMatch(l -> "page-ended".equals(l.fields().get("event.action"))
                    && l.fields().containsKey("edt.devices") && l.fields().containsKey("edt.verdicts")));
        }
    }

    @Test
    void batch_start_received_is_written_once_even_when_the_batch_continues_as_new_twice() {
        try (LogCapture log = new LogCapture()) {
            run(new BatchWorkflowImpl.BatchSettings(2, 2, 1, 2));

            assertEquals(2, log.lines(BatchWorkflowImpl.class, "Batch continues as new").size(), "three runs");
            var lines = log.lines("edt.interaction").stream()
                    .filter(l -> "batch-start-received".equals(l.fields().get("event.action"))).toList();
            assertEquals(1, lines.size());
            var fields = lines.get(0).fields();
            assertEquals(BATCH_ID, fields.get("edt.batch_id"));
            assertEquals("edt.orchestratorReqGrpc", fields.get("edt.interface"));
            assertEquals("inbound", fields.get("network.direction"));
            assertEquals("success", fields.get("event.outcome"));
        }
    }

    @Test
    void continuing_as_new_is_logged_with_the_page_as_a_field() {
        try (LogCapture log = new LogCapture()) {
            run(new BatchWorkflowImpl.BatchSettings(2, 2, 1, 2));

            var lines = log.lines(BatchWorkflowImpl.class, "Batch continues as new");
            assertEquals(2, lines.size(), "one Page per run: three runs, so two continues");
            assertTrue(lines.stream().allMatch(l -> "batch-continued-as-new".equals(l.fields().get("event.action"))
                    && BATCH_ID.equals(l.fields().get("edt.batch_id")) && l.fields().containsKey("edt.page")));
        }
    }
}
