package prepare.edt.orchestrator.usecase;

import prepare.edt.orchestrator.entity.Facts;

/**
 * Port: decodes the payload of a Response and takes the Facts the Flow decides on.
 *
 * In the model this is Parsec, existing CSW software that decodes the GBCS payload. We do not have it, so
 * a PLACEHOLDER implementation reads the simulator's placeholder XML. Parsec plugs in behind this same
 * interface, without changing the workflow.
 */
public interface ResponseDecoder {

    /**
     * @param srv         the Service Reference Variant of the request: it says what the Response contains
     * @param responseXml the Response as received (its DSP signature already checked by the Response Listener)
     * @throws UndecodableResponseException if the payload cannot be decoded (a retry would not help)
     */
    Facts decode(String srv, String responseXml);

    final class UndecodableResponseException extends RuntimeException {
        public UndecodableResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
