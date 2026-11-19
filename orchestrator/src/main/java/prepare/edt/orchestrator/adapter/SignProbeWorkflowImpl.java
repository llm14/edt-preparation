package prepare.edt.orchestrator.adapter;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import org.slf4j.Logger;
import prepare.edt.orchestrator.infrastructure.LogFields;

/**
 * Workflow code must be deterministic: Temporal replays it from its history to rebuild its state.
 * So: no I/O, no threads, no system clock, no random. Side effects go into activities.
 * Logging uses Workflow.getLogger, which stays silent during replays.
 */
public class SignProbeWorkflowImpl implements SignProbeWorkflow {

    private static final Logger LOG = Workflow.getLogger(SignProbeWorkflowImpl.class);

    private final SignActivity signer;

    /** The signing task queue comes from configuration, passed in by the worker. */
    public SignProbeWorkflowImpl(String signingTaskQueue) {
        this.signer = Workflow.newActivityStub(SignActivity.class,
                ActivityOptions.newBuilder()
                        // Schedule the activity on the DUIS Signer's queue, not on our own.
                        .setTaskQueue(signingTaskQueue)
                        // Maximum time for one attempt, once a signer worker has picked it up.
                        .setStartToCloseTimeout(Duration.ofSeconds(30))
                        // Retry technical errors a few times. A refusal is a result, so it is never retried.
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                        .build());
    }

    @Override
    public SignOutcome probeSigning(String unsignedServiceRequest) {
        try (var fields = LogFields.of("event.action", "sign-probe-started")) {
            LOG.info("Asking the DUIS Signer to sign");
        }
        SignOutcome outcome = signer.sign(unsignedServiceRequest);
        try (var fields = LogFields.of("event.action", "sign-probe-outcome", "edt.status", outcome.status())) {
            if (outcome.refusalReason() != null) {
                fields.add("edt.refusal_reason", outcome.refusalReason());
            }
            LOG.info("Sign outcome");
        }
        return outcome;
    }
}
