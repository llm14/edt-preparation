package prepare.edt.orchestrator.adapter;

import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;
import prepare.edt.orchestrator.entity.RequestId;

/**
 * PLACEHOLDER Flow until the Flow Catalogue (model 010.020) is built: whatever the row's Flow, it runs ONE Service
 * Request and turns its outcome into a Verdict. Enough to run a Manifest end to end.
 *
 *   Service Request: 8.2 for a Communications Hub (CHF), 4.1.1 for meters (both in the signer's placeholder allowlist)
 *   Verdict:         RESPONDED -> RESPONDS;  TIMED_OUT or a DCC Alert N12/N13 -> NO_RESPONSE;  otherwise NOT_DIAGNOSED
 *
 * The real Flow takes the Service Requests, their order and decisions from the Flow Catalogue, publishes each step
 * to Kafka, and decides the Verdict on the Facts. [CONFIRM] Verdict names with DCC.
 */
public class PlaceholderFlowWorkflowImpl implements FlowWorkflow {

    private static final Logger LOG = Workflow.getLogger(PlaceholderFlowWorkflowImpl.class);

    /** PLACEHOLDER: the Other User ID of the signer's placeholder configuration. */
    static final String ORIGINATOR = "90-B3-D5-1F-30-01-00-01";

    static final String RESPONDS = "RESPONDS";
    static final String NO_RESPONSE = "NO_RESPONSE";
    static final String NOT_DIAGNOSED = "NOT_DIAGNOSED";

    @Override
    public String run(FlowInput input) {
        String srv = "CHF".equals(input.deviceType()) ? "8.2" : "4.1.1";
        // Workflow.currentTimeMillis: deterministic on replay (the placeholder counter of the Request ID).
        RequestId requestId = RequestId.placeholder(ORIGINATOR, input.deviceId(), Workflow.currentTimeMillis());

        ServiceRequestWorkflow serviceRequest = Workflow.newChildWorkflowStub(ServiceRequestWorkflow.class,
                ChildWorkflowOptions.newBuilder().setWorkflowId(requestId.workflowId()).build());
        ServiceRequestOutcome outcome = serviceRequest.run(new ServiceRequestInput(requestId.value(), input.batchId(),
                ORIGINATOR, input.deviceId(), input.deviceType(), srv));

        String verdict = switch (outcome.status()) {
            case ServiceRequestOutcome.RESPONDED -> RESPONDS;
            case ServiceRequestOutcome.TIMED_OUT, ServiceRequestOutcome.FAILED_ALERT -> NO_RESPONSE;
            default -> NOT_DIAGNOSED;
        };
        LOG.info("Flow for row {} ({}): {} -> Verdict {}", input.rowNumber(), input.deviceId(), outcome.status(), verdict);
        return verdict;
    }
}
