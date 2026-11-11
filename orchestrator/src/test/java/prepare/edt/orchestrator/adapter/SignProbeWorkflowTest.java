package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Runs the workflow on an in-memory Temporal server (no Docker).
 * A fake Sign activity, registered only on the signing task queue, stands in for the DUIS Signer:
 * if the workflow scheduled the activity on the wrong queue, nothing would ever run it.
 */
@Timeout(10)
class SignProbeWorkflowTest {

    private static final String ORCHESTRATOR_QUEUE = "orchestrator";
    private static final String SIGNING_QUEUE = "duis-signing";

    private TestWorkflowEnvironment env;
    private Worker signerWorker;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        env.newWorker(ORCHESTRATOR_QUEUE).registerWorkflowImplementationFactory(
                SignProbeWorkflow.class, () -> new SignProbeWorkflowImpl(SIGNING_QUEUE));
        signerWorker = env.newWorker(SIGNING_QUEUE);
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void returns_what_the_signer_returns() {
        signerWorker.registerActivitiesImplementations((SignActivity) request -> "signed:" + request);
        env.start();

        String result = newWorkflow().run("<request/>");

        assertEquals("signed:<request/>", result);
    }

    @Test
    void fails_without_retry_when_the_signer_refuses() {
        AtomicInteger calls = new AtomicInteger();
        signerWorker.registerActivitiesImplementations((SignActivity) request -> {
            calls.incrementAndGet();
            throw ApplicationFailure.newNonRetryableFailure("refused", "NotImplemented");
        });
        env.start();

        assertThrows(WorkflowFailedException.class, () -> newWorkflow().run("<request/>"));
        assertEquals(1, calls.get(), "a non-retryable failure must not be retried");
    }

    private SignProbeWorkflow newWorkflow() {
        return env.getWorkflowClient().newWorkflowStub(SignProbeWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(ORCHESTRATOR_QUEUE).build());
    }
}
