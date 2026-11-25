package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Workflow;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import prepare.edt.orchestrator.adapter.FlowWorkflow.FlowInput;
import prepare.edt.orchestrator.entity.Facts;

/**
 * The Flow workflow with the real Flow Catalogue activity and a stand-in Service Request workflow. The stand-in decides the
 * outcome from the last group of the device ID, and for A6 also from the SRV:
 *
 *   A0 Response, A1 DCC Alert N12, A3 timeout, A4 rejected by the DSP,
 *   A6 Response to the first SRV of a Flow (8.9 or 6.2.9) and a timeout to the next, A7 Response with MessageSuccess=false.
 */
@Timeout(20)
class FlowWorkflowTest {

    record Request(String workflowId, String requestId, String srv, String batchId) {
    }

    static final List<Request> REQUESTS = new CopyOnWriteArrayList<>();

    public static class StandInServiceRequest implements ServiceRequestWorkflow {
        @Override
        public ServiceRequestOutcome executeServiceRequest(ServiceRequestInput input) {
            REQUESTS.add(new Request(Workflow.getInfo().getWorkflowId(), input.requestId(), input.srv(), input.batchId()));
            String group = input.targetDevice().substring(21);
            boolean firstSrv = "8.9".equals(input.srv()) || "6.2.9".equals(input.srv());
            String status = switch (group) {
                case "A0", "A7" -> ServiceRequestOutcome.RESPONDED;
                case "A1" -> ServiceRequestOutcome.FAILED_ALERT;
                case "A3" -> ServiceRequestOutcome.TIMED_OUT;
                case "A6" -> firstSrv ? ServiceRequestOutcome.RESPONDED : ServiceRequestOutcome.TIMED_OUT;
                default -> ServiceRequestOutcome.REJECTED_BY_DSP;
            };
            Facts facts = ServiceRequestOutcome.RESPONDED.equals(status)
                    ? Facts.of(Map.of("MessageSuccess", String.valueOf(!"A7".equals(group)))) : null;
            return new ServiceRequestOutcome(status, input.requestId(), facts, null, null);
        }

        @Override
        public void onDspMessage(DspMessage message) {
        }
    }

    TestWorkflowEnvironment env;
    final RecordingResultPublisher kafka = new RecordingResultPublisher();

    @AfterEach
    void tearDown() {
        if (env != null) {          // the verdict rule test starts no Temporal environment
            env.close();
        }
    }

    String run(String deviceId, String deviceType, String flow) {
        env = TestWorkflowEnvironment.newInstance();
        var worker = env.newWorker("orchestrator");
        worker.registerWorkflowImplementationTypes(FlowWorkflowImpl.class, StandInServiceRequest.class);
        worker.registerActivitiesImplementations(kafka, new FlowCatalogueActivityImpl());
        env.start();
        REQUESTS.clear();
        return env.getWorkflowClient().newWorkflowStub(FlowWorkflow.class, WorkflowOptions.newBuilder()
                        .setWorkflowId("batch-1/row-1").setTaskQueue("orchestrator").build())
                .diagnoseDevice(new FlowInput("batch-1", 1, deviceId, deviceType, flow));
    }

    static String device(String group) {
        return "00-DB-12-34-56-78-90-" + group;
    }

    // --- 4.1: a row the Flow cannot run ---

    @Test
    void a_flow_that_is_not_in_the_catalogue_sends_nothing_and_is_not_diagnosed() {
        assertEquals("NOT_DIAGNOSED", run(device("A0"), "ESME", "flow-1"));

        assertTrue(REQUESTS.isEmpty());
        assertTrue(kafka.steps.isEmpty());
        assertEquals(1, kafka.devices.size());
        assertEquals("flow-1", kafka.devices.get(0).flow(), "the row's own Flow name is published");
        assertEquals("NOT_DIAGNOSED", kafka.devices.get(0).verdict());
    }

    @Test
    void a_device_type_the_flow_does_not_accept_sends_nothing_and_is_not_diagnosed() {
        assertEquals("NOT_DIAGNOSED", run(device("A0"), "ESME", "hub-triage"));

        assertTrue(REQUESTS.isEmpty());
        assertTrue(kafka.steps.isEmpty());
        assertEquals("NOT_DIAGNOSED", kafka.devices.get(0).verdict());
    }

    @Test
    void a_refused_row_is_logged_with_its_flow_and_device_type_and_the_workflow_does_not_fail() {
        try (LogCapture log = new LogCapture()) {
            run(device("A0"), "CHF", "meter-triage");   // 6.2.9 is not for a hub: refused, no exception

            var lines = log.lines(FlowWorkflowImpl.class, "Flow refused");
            assertEquals(1, lines.size(), "constant message text, written once");
            var fields = lines.get(0).fields();
            assertEquals("flow-refused", fields.get("event.action"));
            assertEquals("meter-triage", fields.get("edt.flow"));
            assertEquals("CHF", fields.get("edt.device_type"));
            assertEquals("the Flow does not accept this device type", fields.get("edt.reason"));
        }
    }

    // --- 4.2: the steps ---

    @Test
    void the_steps_run_in_order_and_each_has_its_own_request_id() {
        assertEquals("RESPONDS", run(device("A0"), "ESME", "meter-triage"));

        assertEquals(List.of("6.2.9", "11.2"), REQUESTS.stream().map(Request::srv).toList());
        assertTrue(REQUESTS.get(0).workflowId().startsWith("service-request-90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A0:"),
                "the workflow ID comes from the Request ID, as the Response Listener expects");
        assertEquals(List.of("batch-1", "batch-1"), REQUESTS.stream().map(Request::batchId).toList());
        assertNotEquals(REQUESTS.get(0).requestId(), REQUESTS.get(1).requestId());
        assertNotEquals(REQUESTS.get(0).workflowId(), REQUESTS.get(1).workflowId());
        assertTrue(counter(REQUESTS.get(1)) > counter(REQUESTS.get(0)), "the second counter is greater than the first");
    }

    @Test
    void a_hub_runs_8_9_then_6_30() {
        assertEquals("RESPONDS", run(device("A0"), "CHF", "hub-triage"));

        assertEquals(List.of("8.9", "6.30"), REQUESTS.stream().map(Request::srv).toList());
    }

    @Test
    void a_gas_meter_runs_the_meter_flow_like_an_electricity_meter() {
        assertEquals("RESPONDS", run(device("A0"), "GSME", "meter-triage"));

        assertEquals(List.of("6.2.9", "11.2"), REQUESTS.stream().map(Request::srv).toList());
    }

    @Test
    void the_flow_stops_when_the_first_step_gets_no_response() {
        assertEquals("NO_RESPONSE", run(device("A3"), "ESME", "meter-triage"));

        assertEquals(1, REQUESTS.size(), "the second Service Request is not sent");
    }

    @Test
    void a_response_with_message_success_false_is_still_a_response_so_the_flow_goes_on() {
        assertEquals("RESPONDS", run(device("A7"), "ESME", "meter-triage"));

        assertEquals(2, REQUESTS.size());
        assertEquals("false", kafka.steps.get(0).facts().get("MessageSuccess"));
    }

    // --- 4.3: the Verdict and Kafka ---

    @Test
    void both_steps_answered_is_responds_with_two_step_events_and_one_device_result() {
        assertEquals("RESPONDS", run(device("A0"), "ESME", "meter-triage"));

        assertEquals(2, kafka.steps.size());
        assertEquals("batch-1/row-1/step-1", kafka.steps.get(0).eventId());
        assertEquals("batch-1/row-1/step-2", kafka.steps.get(1).eventId());
        assertEquals(List.of(1, 2), kafka.steps.stream().map(ResultPublisherActivity.FlowStep::step).toList());
        assertEquals(List.of("6.2.9", "11.2"), kafka.steps.stream().map(ResultPublisherActivity.FlowStep::srv).toList());
        assertEquals(List.of("RESPONDED", "RESPONDED"), kafka.steps.stream().map(ResultPublisherActivity.FlowStep::status).toList());
        assertEquals(REQUESTS.get(0).requestId(), kafka.steps.get(0).requestId());
        assertEquals(1, kafka.devices.size());
        assertEquals("batch-1/row-1", kafka.devices.get(0).eventId());
        assertEquals("meter-triage", kafka.devices.get(0).flow());
        assertEquals("RESPONDS", kafka.devices.get(0).verdict());
    }

    @Test
    void a_device_that_answers_the_first_step_and_is_silent_on_the_second_still_responds() {
        assertEquals("RESPONDS", run(device("A6"), "ESME", "meter-triage"));

        assertEquals(List.of("RESPONDED", "TIMED_OUT"), kafka.steps.stream().map(ResultPublisherActivity.FlowStep::status).toList());
        assertEquals("RESPONDS", kafka.devices.get(0).verdict());
    }

    @Test
    void silent_from_the_start_is_no_response_with_one_step_event() {
        assertEquals("NO_RESPONSE", run(device("A3"), "ESME", "meter-triage"));

        assertEquals(1, kafka.steps.size());
        assertEquals("TIMED_OUT", kafka.steps.get(0).status());
        assertEquals("NO_RESPONSE", kafka.devices.get(0).verdict());
    }

    @Test
    void an_alert_on_the_first_step_is_no_response() {
        assertEquals("NO_RESPONSE", run(device("A1"), "ESME", "meter-triage"));
        assertEquals(1, REQUESTS.size());
    }

    @Test
    void a_request_the_dsp_rejects_is_not_diagnosed() {
        assertEquals("NOT_DIAGNOSED", run(device("A4"), "ESME", "meter-triage"));

        assertEquals(1, kafka.steps.size());
        assertEquals("REJECTED_BY_DSP", kafka.steps.get(0).status());
        assertEquals("NOT_DIAGNOSED", kafka.devices.get(0).verdict());
    }

    @Test
    void the_verdict_rule_over_statuses() {
        assertEquals("RESPONDS", FlowWorkflowImpl.verdictOf(List.of("RESPONDED", "RESPONDED")));
        assertEquals("RESPONDS", FlowWorkflowImpl.verdictOf(List.of("RESPONDED", "TIMED_OUT")));
        assertEquals("NO_RESPONSE", FlowWorkflowImpl.verdictOf(List.of("TIMED_OUT")));
        assertEquals("NO_RESPONSE", FlowWorkflowImpl.verdictOf(List.of("FAILED_ALERT")));
        assertEquals("NOT_DIAGNOSED", FlowWorkflowImpl.verdictOf(List.of("REFUSED_BY_SIGNER")));
        assertEquals("NOT_DIAGNOSED", FlowWorkflowImpl.verdictOf(List.of("SEND_FAILED")));
        assertEquals("NOT_DIAGNOSED", FlowWorkflowImpl.verdictOf(List.of("DECODE_FAILED")));
    }

    @Test
    void the_flow_line_carries_its_data_as_fields() {
        try (LogCapture log = new LogCapture()) {
            run(device("A0"), "ESME", "meter-triage");

            var lines = log.lines(FlowWorkflowImpl.class, "Flow ended");
            assertEquals(1, lines.size(), "the message text is constant, and the line is written once");
            var fields = lines.get(0).fields();
            assertEquals("flow-ended", fields.get("event.action"));
            assertEquals("1", fields.get("edt.manifest.row"));
            assertEquals("00-DB-12-34-56-78-90-A0", fields.get("edt.device_id"));
            assertEquals("meter-triage", fields.get("edt.flow"));
            assertEquals("2", fields.get("edt.steps"));
            assertEquals("RESPONDED", fields.get("edt.status"));
            assertEquals("RESPONDS", fields.get("edt.verdict"));
        }
    }

    static long counter(Request request) {
        return Long.parseLong(request.requestId().substring(request.requestId().lastIndexOf(':') + 1));
    }
}
