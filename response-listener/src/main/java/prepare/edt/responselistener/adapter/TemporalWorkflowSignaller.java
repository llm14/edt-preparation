package prepare.edt.responselistener.adapter;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import prepare.edt.responselistener.entity.DspMessage;
import prepare.edt.responselistener.usecase.WorkflowSignaller;

/**
 * Signals a workflow through the Temporal Server. The Response Listener never talks to the Orchestrator:
 * the server stores the signal in the workflow's history and hands it to whichever Orchestrator runs it.
 *
 * An "untyped" stub is used: we only know the workflow ID, the signal name and the payload,
 * which is all the contract between the two applications is.
 */
public class TemporalWorkflowSignaller implements WorkflowSignaller {

    /** The signal name the Service Request workflow declares (@SignalMethod(name = "DspMessage")). */
    static final String SIGNAL = "DspMessage";

    private final WorkflowClient client;

    public TemporalWorkflowSignaller(WorkflowClient client) {
        this.client = client;
    }

    @Override
    public boolean signal(String workflowId, DspMessage message) {
        var payload = new DspMessageSignal(message.kind().name(), message.alertCode(), message.xml());
        try {
            client.newUntypedWorkflowStub(workflowId).signal(SIGNAL, payload);
            return true;
        } catch (WorkflowNotFoundException e) {
            // No running workflow with this ID: it never existed, or it has already completed (for example timed out).
            return false;
        } catch (RuntimeException e) {
            throw new SignallerUnavailableException("could not signal " + workflowId + ": " + e.getMessage(), e);
        }
    }
}
