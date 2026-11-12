package prepare.edt.responselistener.usecase;

import prepare.edt.responselistener.entity.DspMessage;

/** Port: wakes the workflow that waits for a DSP message (the "Workflow Signaller" of the model). */
public interface WorkflowSignaller {

    /**
     * Sends the message to the running workflow with this ID.
     *
     * @return true if delivered; false if no such workflow is running (it never existed, or has already ended)
     * @throws SignallerUnavailableException if the message could not be delivered now (Temporal unreachable)
     */
    boolean signal(String workflowId, DspMessage message);

    final class SignallerUnavailableException extends RuntimeException {
        public SignallerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
