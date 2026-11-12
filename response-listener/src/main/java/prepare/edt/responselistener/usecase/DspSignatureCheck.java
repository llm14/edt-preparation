package prepare.edt.responselistener.usecase;

/** Port: checks that a message really comes from the DSP (its XML signature, with the trusted DSP certificate). */
public interface DspSignatureCheck {

    /** @throws InvalidDspSignatureException if the message is not signed by the DSP, or was changed after signing */
    void verify(String body);

    final class InvalidDspSignatureException extends RuntimeException {
        public InvalidDspSignatureException(String reason) {
            super(reason);
        }
    }
}
