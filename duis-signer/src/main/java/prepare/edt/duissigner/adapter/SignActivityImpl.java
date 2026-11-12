package prepare.edt.duissigner.adapter;

import prepare.edt.duissigner.usecase.SignResult;
import prepare.edt.duissigner.usecase.SignServiceRequest;

/**
 * Adapter between Temporal and the use case: calls SignServiceRequest and turns its result into a SignOutcome.
 */
public final class SignActivityImpl implements SignActivity {

    private final SignServiceRequest useCase;

    public SignActivityImpl(SignServiceRequest useCase) {
        this.useCase = useCase;
    }

    @Override
    public SignOutcome sign(String unsignedServiceRequest) {
        return switch (useCase.sign(unsignedServiceRequest)) {
            case SignResult.Signed signed -> new SignOutcome(SignOutcome.SIGNED, signed.requestId(),
                    signed.role().name(), signed.signedXml(), null);
            case SignResult.Refused refused -> new SignOutcome(SignOutcome.REFUSED, refused.requestId(),
                    null, null, refused.reason());
        };
    }
}
