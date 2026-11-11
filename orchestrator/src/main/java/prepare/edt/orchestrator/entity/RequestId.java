package prepare.edt.orchestrator.entity;

import java.util.Objects;

/**
 * A DUIS Request ID: originator ID, target device ID and originator counter.
 *
 * It is created by the EDT before the Service Request is sent (the Flow passes it to the Service Request workflow),
 * written into the request by the Request Builder, and echoed back by the DSP in the Response or DCC Alert.
 * The Response Listener reads it there and signals the workflow whose ID is derived from it.
 *
 * TO CONFIRM with DCC / the lead: the exact text format and how the counter is generated
 * (DUIS expects it to increase per originator). See placeholder().
 */
public record RequestId(String originatorId, String targetId, long counter) {

    public RequestId {
        Objects.requireNonNull(originatorId, "originatorId");
        Objects.requireNonNull(targetId, "targetId");
    }

    /**
     * PLACEHOLDER counter: the caller passes a time in milliseconds (in a workflow: Workflow.currentTimeMillis(),
     * which is deterministic). Two requests from the same originator in the same millisecond would clash.
     */
    public static RequestId placeholder(String originatorId, String targetId, long epochMillis) {
        return new RequestId(originatorId, targetId, epochMillis);
    }

    /** "originator:target:counter". */
    public String value() {
        return originatorId + ":" + targetId + ":" + counter;
    }

    /**
     * The Temporal workflow ID of this request's Service Request workflow.
     * The Response Listener applies the same rule (its own copy) to find the workflow.
     */
    public static String workflowId(String requestId) {
        return "service-request-" + requestId;
    }

    public String workflowId() {
        return workflowId(value());
    }

    @Override
    public String toString() {
        return value();
    }
}
