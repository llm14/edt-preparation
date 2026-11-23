package prepare.edt.orchestrator.adapter;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * A throwaway workflow that only asks the DUIS Signer to sign one Service Request.
 * It proves the Orchestrator -> Temporal -> DUIS Signer round trip.
 * The real Service Request workflow will replace it.
 *
 * Start it from the Temporal CLI with the workflow type "SignProbe" on the task queue "orchestrator",
 * with two inputs: an unsigned DUIS Service Request XML and the type of its target device (for example "ESME").
 */
@WorkflowInterface
public interface SignProbeWorkflow {

    @WorkflowMethod(name = "SignProbe")
    SignOutcome probeSigning(String unsignedServiceRequest, String deviceType);
}
