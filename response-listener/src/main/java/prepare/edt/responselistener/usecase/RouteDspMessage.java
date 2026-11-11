package prepare.edt.responselistener.usecase;

import prepare.edt.responselistener.entity.DspMessage;
import prepare.edt.responselistener.entity.ServiceRequestWorkflowId;

/**
 * Use case: a Response or DCC Alert arrives; deliver it to the Service Request workflow it belongs to.
 *
 *   read the message -> (check the DSP signature: a later step) -> Request ID -> workflow ID -> signal
 *
 * Exceptions are left to the caller, which turns them into HTTP answers:
 * UnreadableMessageException (no point retrying) and SignallerUnavailableException (worth retrying).
 */
public class RouteDspMessage {

    private final DspMessageReader reader;
    private final WorkflowSignaller signaller;

    public RouteDspMessage(DspMessageReader reader, WorkflowSignaller signaller) {
        this.reader = reader;
        this.signaller = signaller;
    }

    public Result route(String body) {
        DspMessage message = reader.read(body);
        // TODO later step: verify the DSP signature here, before anything is trusted.
        String workflowId = ServiceRequestWorkflowId.of(message.requestId());
        return signaller.signal(workflowId, message)
                ? new Result.Signalled(message, workflowId)
                : new Result.Orphan(message, workflowId);
    }

    /** What happened to the message. */
    public sealed interface Result {
        DspMessage message();

        String workflowId();

        /** The waiting workflow got it. */
        record Signalled(DspMessage message, String workflowId) implements Result {
        }

        /** No workflow is waiting (already ended, for example timed out). Logged; a retry would not help. */
        record Orphan(DspMessage message, String workflowId) implements Result {
        }
    }
}
