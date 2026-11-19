package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void the_probe_lines_carry_their_data_as_fields() {
        try (LogCapture log = new LogCapture()) {
            signerWorker.registerActivitiesImplementations((SignActivity) request ->
                    new SignOutcome(SignOutcome.REFUSED, "REQ-1", null, null, "SRV not allowed"));
            env.start();

            newWorkflow().probeSigning("<request/>");

            var started = log.lines(SignProbeWorkflowImpl.class, "Asking the DUIS Signer to sign");
            assertEquals(1, started.size());
            assertEquals("sign-probe-started", started.get(0).fields().get("event.action"));

            var outcome = log.lines(SignProbeWorkflowImpl.class, "Sign outcome");
            assertEquals(1, outcome.size(), "the message text is constant");
            assertEquals("sign-probe-outcome", outcome.get(0).fields().get("event.action"));
            assertEquals("REFUSED", outcome.get(0).fields().get("edt.status"));
            assertEquals("SRV not allowed", outcome.get(0).fields().get("edt.refusal_reason"));
        }
    }

    @Test
    void a_signed_outcome_has_no_refusal_reason_field() {
        try (LogCapture log = new LogCapture()) {
            signerWorker.registerActivitiesImplementations((SignActivity) request ->
                    new SignOutcome(SignOutcome.SIGNED, "REQ-1", "OTHER_USER", request + "<Signature/>", null));
            env.start();

            newWorkflow().probeSigning("<request/>");

            var outcome = log.lines(SignProbeWorkflowImpl.class, "Sign outcome");
            assertEquals(1, outcome.size());
            assertEquals("SIGNED", outcome.get(0).fields().get("edt.status"));
            assertFalse(outcome.get(0).fields().containsKey("edt.refusal_reason"));
        }
    }

    private SignProbeWorkflow newWorkflow() {
        return env.getWorkflowClient().newWorkflowStub(SignProbeWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(ORCHESTRATOR_QUEUE).build());
    }
}
