package prepare.edt.responselistener.adapter;

/**
 * The payload of the "DspMessage" signal, as the Service Request workflow expects it.
 * Temporal sends it as JSON, so this is an identical copy of the Orchestrator's DspMessage record
 * (same field names), not a shared class.
 */
public record DspMessageSignal(String type, String alertCode, String xml) {
}
