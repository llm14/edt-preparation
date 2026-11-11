package prepare.edt.responselistener.entity;

import java.util.Objects;

/**
 * A message from the DSP about one Service Request: the asynchronous Response, or a DCC Alert.
 *
 * @param kind      RESPONSE or DCC_ALERT
 * @param requestId the DUIS Request ID the DSP echoes back: it says which Service Request this is about
 * @param alertCode for a DCC Alert, its code (for example N12); null for a Response
 * @param xml       the message exactly as received
 */
public record DspMessage(Kind kind, String requestId, String alertCode, String xml) {

    public enum Kind { RESPONSE, DCC_ALERT }

    public DspMessage {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(xml, "xml");
        if (kind == Kind.DCC_ALERT && alertCode == null) {
            throw new IllegalArgumentException("a DCC Alert needs an alert code");
        }
    }
}
