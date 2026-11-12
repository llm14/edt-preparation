package prepare.edt.orchestrator.adapter;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** One device Flow (model 010.020): runs one approved Flow on one device and returns its Verdict. */
@WorkflowInterface
public interface FlowWorkflow {

    @WorkflowMethod(name = "Flow")
    String run(FlowInput input);

    record FlowInput(String batchId, long rowNumber, String deviceId, String deviceType, String flow) {
    }
}
