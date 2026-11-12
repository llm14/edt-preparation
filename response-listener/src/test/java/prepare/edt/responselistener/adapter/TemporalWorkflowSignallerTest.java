package prepare.edt.responselistener.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import prepare.edt.responselistener.entity.DspMessage;

/**
 * The signaller against Temporal's in-memory server, with a stand-in for the Orchestrator's workflow.
 *
 * The stand-in declares the same workflow type, signal name and payload fields as the Orchestrator,
 * so this test also checks our side of the contract: what we send can be read as their DspMessage.
 */
class TemporalWorkflowSignallerTest {

    /** Same field names as the Orchestrator's DspMessage record, but its own class: it only shares JSON. */
    public record OrchestratorDspMessage(String type, String alertCode, String xml) {
    }

    @WorkflowInterface
    public interface StandInServiceRequest {
        @WorkflowMethod(name = "ServiceRequest")
        String run();

        @SignalMethod(name = "DspMessage")
        void onDspMessage(OrchestratorDspMessage message);
    }

    /** Waits for one signal and returns what it got. */
    public static class StandInServiceRequestImpl implements StandInServiceRequest {
        private OrchestratorDspMessage received;

        @Override
        public String run() {
            Workflow.await(() -> received != null);
            return received.type() + "/" + received.alertCode() + "/" + received.xml();
        }

        @Override
        public void onDspMessage(OrchestratorDspMessage message) {
            received = message;
        }
    }

    static final String QUEUE = "orchestrator";

    TestWorkflowEnvironment env;
    WorkflowClient client;
    TemporalWorkflowSignaller signaller;

    @BeforeEach
    void start() {
        env = TestWorkflowEnvironment.newInstance();
        env.newWorker(QUEUE).registerWorkflowImplementationTypes(StandInServiceRequestImpl.class);
        env.start();
        client = env.getWorkflowClient();
        signaller = new TemporalWorkflowSignaller(client);
    }

    @AfterEach
    void stop() {
        env.close();
    }

    @Test
    void delivers_the_message_to_the_waiting_workflow() {
        StandInServiceRequest workflow = startWorkflow("service-request-o:d:1");

        boolean delivered = signaller.signal("service-request-o:d:1",
                new DspMessage(DspMessage.Kind.DCC_ALERT, "o:d:1", "N12", "<DCCAlert/>"));

        assertTrue(delivered);
        assertEquals("DCC_ALERT/N12/<DCCAlert/>", WorkflowStub.fromTyped(workflow).getResult(String.class));
    }

    @Test
    void no_workflow_with_that_id_is_not_delivered() {
        assertFalse(signaller.signal("service-request-unknown", response()));
    }

    @Test
    void a_workflow_that_has_ended_is_not_delivered() {
        StandInServiceRequest workflow = startWorkflow("service-request-o:d:2");
        signaller.signal("service-request-o:d:2", response());
        WorkflowStub.fromTyped(workflow).getResult(String.class);   // now completed

        assertFalse(signaller.signal("service-request-o:d:2", response()));
    }

    StandInServiceRequest startWorkflow(String workflowId) {
        StandInServiceRequest workflow = client.newWorkflowStub(StandInServiceRequest.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(QUEUE).build());
        WorkflowClient.start(workflow::run);
        return workflow;
    }

    static DspMessage response() {
        return new DspMessage(DspMessage.Kind.RESPONSE, "o:d", null, "<Response/>");
    }
}
