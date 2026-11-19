package prepare.edt.orchestrator.adapter;

import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One Service Request: build, sign, send once, then wait durably for the Response (architecture model).
 *
 * Started by the Flow with workflow ID = RequestId.workflowId(requestId), so the Response Listener
 * can find it from the Request ID alone. Names are explicit because the Response Listener keeps its own copy.
 */
@WorkflowInterface
public interface ServiceRequestWorkflow {

    @WorkflowMethod(name = "ServiceRequest")
    ServiceRequestOutcome executeServiceRequest(ServiceRequestInput input);

    /** Called (through Temporal) by the Response Listener with the Response or a DCC Alert. */
    @SignalMethod(name = "DspMessage")
    void onDspMessage(DspMessage message);
}
