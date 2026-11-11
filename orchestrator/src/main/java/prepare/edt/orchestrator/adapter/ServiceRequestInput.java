package prepare.edt.orchestrator.adapter;

/**
 * What the Flow gives the Service Request workflow: everything needed to build one Service Request.
 *
 * @param requestId    the DUIS Request ID, created by the Flow (the workflow ID is derived from it)
 * @param batchId      the Batch this request belongs to: the fairness key of the send queue
 * @param originator   the User ID that sends it
 * @param targetDevice the device (or Communications Hub) it is for
 * @param deviceType   for example ESME, GSME, CHF
 * @param srv          the Service Reference Variant
 */
public record ServiceRequestInput(String requestId, String batchId, String originator, String targetDevice, String deviceType,
                                  String srv) {
}
