package prepare.edt.duissigner.adapter;

import io.temporal.failure.ApplicationFailure;

/**
 * Placeholder implementation: refuses every request.
 *
 * It throws a non-retryable failure, so Temporal does not retry it: the caller gets the error at once.
 * Later this will call the signing use case (policy check, then sign through KMS, then audit).
 */
public class SignActivityImpl implements SignActivity {

    @Override
    public String sign(String unsignedServiceRequest) {
        throw ApplicationFailure.newNonRetryableFailure("Signing is not implemented yet", "NotImplemented");
    }
}
