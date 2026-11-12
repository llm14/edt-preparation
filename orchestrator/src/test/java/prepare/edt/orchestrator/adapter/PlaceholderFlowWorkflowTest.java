package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Workflow;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import prepare.edt.orchestrator.adapter.FlowWorkflow.FlowInput;

/** The placeholder Flow with a stand-in Service Request workflow (its outcome from the device ID's last group). */
@Timeout(20)
class PlaceholderFlowWorkflowTest {

    static final List<String> REQUESTS = new CopyOnWriteArrayList<>();

    public static class StandInServiceRequest implements ServiceRequestWorkflow {
        @Override
        public ServiceRequestOutcome run(ServiceRequestInput input) {
            REQUESTS.add(Workflow.getInfo().getWorkflowId() + " " + input.srv() + " " + input.batchId());
            String status = switch (input.targetDevice().substring(21)) {
                case "A0" -> ServiceRequestOutcome.RESPONDED;
                case "A1" -> ServiceRequestOutcome.FAILED_ALERT;
                case "A3" -> ServiceRequestOutcome.TIMED_OUT;
                default -> ServiceRequestOutcome.REJECTED_BY_DSP;
            };
            return new ServiceRequestOutcome(status, input.requestId(), null, null, null);
        }

        @Override
        public void onDspMessage(DspMessage message) {
        }
    }

    TestWorkflowEnvironment env;

    @AfterEach
    void tearDown() {
        env.close();
    }

    String verdict(String deviceId, String deviceType) {
        env = TestWorkflowEnvironment.newInstance();
        env.newWorker("orchestrator").registerWorkflowImplementationTypes(PlaceholderFlowWorkflowImpl.class,
                StandInServiceRequest.class);
        env.start();
        REQUESTS.clear();
        return env.getWorkflowClient().newWorkflowStub(FlowWorkflow.class, WorkflowOptions.newBuilder()
                        .setWorkflowId("batch-1/row-1").setTaskQueue("orchestrator").build())
                .run(new FlowInput("batch-1", 1, deviceId, deviceType, "flow-1"));
    }

    @Test
    void a_response_means_the_device_responds() {
        assertEquals("RESPONDS", verdict("00-DB-12-34-56-78-90-A0", "ESME"));

        assertEquals(1, REQUESTS.size());
        assertTrue(REQUESTS.get(0).startsWith("service-request-90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A0:"),
                "the Service Request workflow ID comes from the Request ID, as the Response Listener expects");
        assertTrue(REQUESTS.get(0).endsWith(" 4.1.1 batch-1"), "meters get 4.1.1; the Batch is the fairness key");
    }

    @Test
    void a_hub_gets_8_2() {
        assertEquals("NO_RESPONSE", verdict("00-DB-12-34-56-78-90-A3", "CHF"));
        assertTrue(REQUESTS.get(0).contains(" 8.2 "));
    }

    @Test
    void an_alert_is_no_response_and_anything_else_is_not_diagnosed() {
        assertEquals("NO_RESPONSE", verdict("00-DB-12-34-56-78-90-A1", "ESME"));
        env.close();
        assertEquals("NOT_DIAGNOSED", verdict("00-DB-12-34-56-78-90-A4", "ESME"));
    }
}
