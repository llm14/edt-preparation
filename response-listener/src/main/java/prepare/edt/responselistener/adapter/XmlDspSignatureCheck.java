package prepare.edt.responselistener.adapter;

import prepare.edt.responselistener.usecase.DspSignatureCheck;

/** Fills the use case's DspSignatureCheck port with the XML signature verifier. */
public class XmlDspSignatureCheck implements DspSignatureCheck {

    private final DspSignatureVerifier verifier;

    public XmlDspSignatureCheck(DspSignatureVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public void verify(String body) {
        DspSignatureVerifier.Verdict verdict = verifier.check(body);
        if (!verdict.valid()) {
            throw new InvalidDspSignatureException(verdict.reason());
        }
    }
}
