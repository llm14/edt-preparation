package prepare.edt.orchestrator.adapter;

/**
 * What the Response Listener signals to a waiting Service Request workflow: the asynchronous Response,
 * or a DCC Alert about the request. Sent as JSON, so the Response Listener keeps an identical copy.
 *
 * @param type      RESPONSE or DCC_ALERT
 * @param alertCode for a DCC Alert, its code (for example N12); otherwise null
 * @param xml       the message as received (already signature-checked by the Response Listener)
 */
public record DspMessage(String type, String alertCode, String xml) {

    public static final String RESPONSE = "RESPONSE";
    public static final String DCC_ALERT = "DCC_ALERT";
}
