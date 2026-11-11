package prepare.edt.orchestrator.adapter;

/**
 * The synchronous DUIS Response the DSP returns when the Service Request is posted:
 * whether the DSP accepted it for delivery. The real answer from the device comes later, asynchronously.
 *
 * @param accepted     true if the DSP accepted the Service Request
 * @param responseCode the DSP's code (PLACEHOLDER values until the DUIS response codes are used)
 * @param detail       a description, mainly for a rejection
 */
public record SyncResponse(boolean accepted, String responseCode, String detail) {
}
