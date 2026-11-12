package prepare.edt.responselistener.usecase;

import prepare.edt.responselistener.entity.DspMessage;
import prepare.edt.responselistener.entity.ServiceRequestWorkflowId;

/**
 * Use case: a Response or DCC Alert arrives; deliver it to the Service Request workflow it belongs to.
 *
 *   check the DSP signature -> read the message -> Request ID -> workflow ID -> signal
 *
 * The signature is checked first, on the message as received: nothing in it (not even the Request ID) is
 * trusted before that.
 *
 * Exceptions are left to the caller, which turns them into HTTP answers: InvalidDspSignatureException and
 * UnreadableMessageException (no point retrying), SignallerUnavailableException (worth retrying).
 */
public class RouteDspMessage {

    private final DspSignatureCheck signatureCheck;
    private final DspMessageReader reader;
    private final WorkflowSignaller signaller;

    public RouteDspMessage(DspSignatureCheck signatureCheck, DspMessageReader reader, WorkflowSignaller signaller) {
        this.signatureCheck = signatureCheck;
        this.reader = reader;
        this.signaller = signaller;
    }

    public Result route(String body) {
        signatureCheck.verify(body);
        DspMessage message = reader.read(body);
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
