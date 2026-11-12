package prepare.edt.responselistener.entity;

/**
 * The rule that turns a DUIS Request ID into the ID of the Service Request workflow waiting for it.
 *
 * The Orchestrator uses the same rule when it starts the workflow (its own copy, in RequestId.workflowId).
 * This is what makes a database unnecessary: the Request ID alone says which workflow to signal.
 */
public final class ServiceRequestWorkflowId {

    private ServiceRequestWorkflowId() {
    }

    public static String of(String requestId) {
        return "service-request-" + requestId;
    }
}
