package prepare.edt.orchestrator.adapter;

import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import prepare.edt.orchestrator.entity.Flow;
import prepare.edt.orchestrator.entity.RequestId;
import prepare.edt.orchestrator.infrastructure.LogFields;

/**
 * One device Flow (model 010.020): runs the steps of the Flow named by the Manifest row, one Service Request after the other, and
 * decides the Verdict. The steps come from the Flow Catalogue activity, so the history keeps them.
 *
 *   refused    a Flow that is not in the catalogue, or a device type its first step does not accept: nothing is sent,
 *              the Verdict is NOT_DIAGNOSED and the workflow does not fail (the Page would count that as FLOW_FAILED)
 *   steps      one Service Request child per step, each with its own Request ID; the Flow goes on only while the step
 *              ended RESPONDED (a Response with MessageSuccess=false is a Response); any other ending stops it
 *   Verdict    RESPONDS if at least one step got a Response; else NO_RESPONSE if the first step timed out or got a DCC Alert
 *              N12 or N13; else NOT_DIAGNOSED. [CONFIRM] the Verdict names and rules with DCC
 *   Kafka      one FlowStep per step run (event ID "<flow workflow id>/step-N"), then one DeviceResult
 *
 * The two Flows in the catalogue are drafts, and the model's decisions "after each Response" are only the fixed rule above.
 */
public class FlowWorkflowImpl implements FlowWorkflow {

    private static final Logger LOG = Workflow.getLogger(FlowWorkflowImpl.class);

    /** PLACEHOLDER: the Other User ID of the signer's placeholder configuration. */
    static final String ORIGINATOR = "90-B3-D5-1F-30-01-00-01";

    static final String RESPONDS = "RESPONDS";
    static final String NO_RESPONSE = "NO_RESPONSE";
    static final String NOT_DIAGNOSED = "NOT_DIAGNOSED";

    private final ResultPublisherActivity publisher = ResultPublisherActivity.stub();
    private final FlowCatalogueActivity catalogue = FlowCatalogueActivity.stub();

    @Override
    public String diagnoseDevice(FlowInput input) {
        FlowCatalogueActivity.Lookup lookup = catalogue.lookup(input.flow());
        if (!lookup.found()) {
            return refused(input, "the Flow is not in the catalogue");
        }
        Flow flow = lookup.flow();
        if (!flow.acceptsDeviceType(input.deviceType())) {
            return refused(input, "the Flow does not accept this device type");
        }

        String flowId = Workflow.getInfo().getWorkflowId();
        List<String> statuses = new ArrayList<>();
        long previousCounter = 0;
        int number = 0;
        for (Flow.Step step : flow.steps()) {
            number++;
            // Workflow.currentTimeMillis: deterministic on replay (the placeholder counter of the Request ID). Strictly
            // increasing inside the Flow, so two steps never share a Request ID, and so never a workflow ID.
            long counter = Math.max(Workflow.currentTimeMillis(), previousCounter + 1);
            previousCounter = counter;
            RequestId requestId = RequestId.placeholder(ORIGINATOR, input.deviceId(), counter);

            // Starts a new process: a Service Request child workflow, with its own workflow ID (from the Request ID) and its
            // own history. The Flow waits here for it to end, because the next step depends on its outcome.
            try (var fields = LogFields.of("event.action", "service-request-started", "edt.batch_id", input.batchId(),
                    "edt.manifest.row", input.rowNumber(), "edt.device_id", input.deviceId(),
                    "edt.request_id", requestId.value(), "edt.step", number, "edt.srv", step.srv())) {
                LOG.info("Service Request started");
            }
            ServiceRequestWorkflow serviceRequest = Workflow.newChildWorkflowStub(ServiceRequestWorkflow.class,
                    ChildWorkflowOptions.newBuilder().setWorkflowId(requestId.workflowId()).build());
            ServiceRequestOutcome outcome = serviceRequest.executeServiceRequest(new ServiceRequestInput(requestId.value(),
                    input.batchId(), ORIGINATOR, input.deviceId(), input.deviceType(), step.srv()));
            statuses.add(outcome.status());

            // Kafka (model 010.020, step 4): the step, as it ends. Event ID from the workflow ID and the step number.
            publisher.publishFlowStep(new ResultPublisherActivity.FlowStep(flowId + "/step-" + number, ResultPublisherActivity.now(),
                    input.batchId(), input.deviceId(), input.rowNumber(), number, step.srv(), requestId.value(), outcome.status(),
                    outcome.alertCode(), outcome.facts() == null ? Map.of() : outcome.facts().values()));

            if (!ServiceRequestOutcome.RESPONDED.equals(outcome.status())) {
                break;
            }
        }

        String verdict = verdictOf(statuses);
        try (var fields = LogFields.of("event.action", "flow-ended", "edt.manifest.row", input.rowNumber(),
                "edt.device_id", input.deviceId(), "edt.flow", input.flow(), "edt.steps", statuses.size(),
                "edt.status", statuses.get(statuses.size() - 1), "edt.verdict", verdict)) {
            LOG.info("Flow ended");
        }
        publishDeviceResult(input, flowId, verdict);
        return verdict;
    }

    /** The Verdict over the statuses of the steps that were run, in order. */
    static String verdictOf(List<String> statuses) {
        if (statuses.contains(ServiceRequestOutcome.RESPONDED)) {
            return RESPONDS;
        }
        return switch (statuses.get(0)) {
            case ServiceRequestOutcome.TIMED_OUT, ServiceRequestOutcome.FAILED_ALERT -> NO_RESPONSE;
            default -> NOT_DIAGNOSED;
        };
    }

    /** Nothing is sent: the row names a Flow the catalogue lacks, or a device type the Flow does not take. */
    private String refused(FlowInput input, String reason) {
        try (var fields = LogFields.of("event.action", "flow-refused", "edt.manifest.row", input.rowNumber(),
                "edt.device_id", input.deviceId(), "edt.flow", input.flow(), "edt.device_type", input.deviceType(),
                "edt.reason", reason, "edt.verdict", NOT_DIAGNOSED)) {
            LOG.warn("Flow refused");
        }
        publishDeviceResult(input, Workflow.getInfo().getWorkflowId(), NOT_DIAGNOSED);
        return NOT_DIAGNOSED;
    }

    private void publishDeviceResult(FlowInput input, String flowId, String verdict) {
        publisher.publishDeviceResult(new ResultPublisherActivity.DeviceResult(flowId, ResultPublisherActivity.now(),
                input.batchId(), input.deviceId(), input.rowNumber(), input.flow(), verdict));
    }
}
