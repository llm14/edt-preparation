package prepare.edt.batchingest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import prepare.edt.batchingest.entity.ManifestRef;

/** On an in-memory Temporal Server, with a stand-in for the Orchestrator's Batch workflow. */
class TemporalBatchStarterTest {

    /** Stand-in: same type name ("Batch") and input fields as the Orchestrator's real workflow will have. */
    @WorkflowInterface
    public interface StandInBatch {
        @WorkflowMethod(name = "Batch")
        String run(BatchInput input);
    }

    public static class StandInBatchImpl implements StandInBatch {
        @Override
        public String run(BatchInput input) {
            if (input.key().startsWith("long")) {
                Workflow.sleep(Duration.ofDays(1));
            }
            return input.bucket() + "/" + input.key() + "@" + input.versionId();
        }
    }

    TestWorkflowEnvironment env;
    TemporalBatchStarter starter;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        env.newWorker("orchestrator").registerWorkflowImplementationTypes(StandInBatchImpl.class);
        env.start();
        starter = new TemporalBatchStarter(env.getWorkflowClient(), "orchestrator");
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void starts_the_batch_on_the_orchestrator_queue_with_the_manifest_as_input() {
        var manifest = new ManifestRef("edt-manifests", "sample.csv", "v1");

        assertTrue(starter.start(manifest));

        String result = env.getWorkflowClient().newUntypedWorkflowStub(manifest.batchWorkflowId())
                .getResult(String.class);
        assertEquals("edt-manifests/sample.csv@v1", result);
    }

    @Test
    void a_second_message_while_the_batch_runs_does_not_start_another() {
        var manifest = new ManifestRef("edt-manifests", "long.csv", "v1");

        assertTrue(starter.start(manifest));
        assertFalse(starter.start(manifest));
    }

    @Test
    void a_late_duplicate_after_the_batch_ended_does_not_run_it_again() {
        var manifest = new ManifestRef("edt-manifests", "sample.csv", "v1");
        assertTrue(starter.start(manifest));
        env.getWorkflowClient().newUntypedWorkflowStub(manifest.batchWorkflowId()).getResult(String.class);

        assertFalse(starter.start(manifest), "REJECT_DUPLICATE");
    }

    @Test
    void a_new_version_of_the_same_file_is_a_new_batch() {
        assertTrue(starter.start(new ManifestRef("edt-manifests", "long.csv", "v1")));
        assertTrue(starter.start(new ManifestRef("edt-manifests", "long.csv", "v2")));
    }
}
