package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
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
@Timeout(20)
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
    void returns_the_signed_outcome() {
        signerWorker.registerActivitiesImplementations((SignActivity) request ->
                new SignOutcome(SignOutcome.SIGNED, "REQ-1", "OTHER_USER", request + "<Signature/>", null));
        env.start();

        SignOutcome outcome = newWorkflow().probeSigning("<request/>");

        assertEquals(new SignOutcome("SIGNED", "REQ-1", "OTHER_USER", "<request/><Signature/>", null), outcome);
    }

    @Test
    void a_refusal_is_a_normal_result_and_is_not_retried() {
        AtomicInteger calls = new AtomicInteger();
        signerWorker.registerActivitiesImplementations((SignActivity) request -> {
            calls.incrementAndGet();
            return new SignOutcome(SignOutcome.REFUSED, "REQ-1", null, null, "SRV not allowed");
        });
        env.start();

        SignOutcome outcome = newWorkflow().probeSigning("<request/>");

        assertEquals("REFUSED", outcome.status());
        assertEquals("SRV not allowed", outcome.refusalReason());
        assertEquals(1, calls.get());
    }

    @Test
    void a_technical_error_is_retried_three_times_then_fails() {
        AtomicInteger calls = new AtomicInteger();
        signerWorker.registerActivitiesImplementations((SignActivity) request -> {
            calls.incrementAndGet();
            throw new IllegalStateException("KMS unreachable");
        });
        env.start();

        assertThrows(WorkflowFailedException.class, () -> newWorkflow().probeSigning("<request/>"));
        assertEquals(3, calls.get(), "maximum 3 attempts");
    }

    private SignProbeWorkflow newWorkflow() {
        return env.getWorkflowClient().newWorkflowStub(SignProbeWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(ORCHESTRATOR_QUEUE).build());
    }
}
