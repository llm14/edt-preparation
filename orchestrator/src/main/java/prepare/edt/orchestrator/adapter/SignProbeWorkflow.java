package prepare.edt.orchestrator.adapter;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * A throwaway workflow that only asks the DUIS Signer to sign one Service Request.
 * It proves the Orchestrator -> Temporal -> DUIS Signer round trip.
 * The real Service Request workflow will replace it.
 *
 * Start it from the Temporal CLI with the workflow type "SignProbe" on the task queue "orchestrator",
 * with an unsigned (PLACEHOLDER format) Service Request XML as input.
 */
@WorkflowInterface
public interface SignProbeWorkflow {

    @WorkflowMethod(name = "SignProbe")
    SignOutcome run(String unsignedServiceRequest);
}
