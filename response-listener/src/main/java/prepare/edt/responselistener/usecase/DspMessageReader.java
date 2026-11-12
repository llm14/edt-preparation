package prepare.edt.responselistener.usecase;

import prepare.edt.responselistener.entity.DspMessage;

/** Port: turns the body the gateway posted into a DspMessage. */
public interface DspMessageReader {

    /** @throws UnreadableMessageException if the body is not a Response or DCC Alert we can read */
    DspMessage read(String body);

    final class UnreadableMessageException extends RuntimeException {
        public UnreadableMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
