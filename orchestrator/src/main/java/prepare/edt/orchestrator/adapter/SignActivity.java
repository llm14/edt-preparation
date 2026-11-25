package prepare.edt.orchestrator.adapter;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The Orchestrator's copy of the DUIS Signer's Sign activity (client side).
 *
 * The Orchestrator never implements it: the DUIS Signer does, in another container.
 * This copy only lets workflows call it. Temporal matches the two by the name "Sign"
 * and the task queue, and the result by its JSON fields (SignOutcome), so both sides must stay identical.
 *
 * A refusal comes back as a normal result (status REFUSED), not as an error.
 */
@ActivityInterface
public interface SignActivity {

    /**
     * @param unsignedServiceRequest the DUIS Service Request, unsigned
     * @param deviceType             the type of the target device (ESME, GSME, CHF, ...): a DUIS request does not carry it,
     *                               and the signer's policy checks it against the SRV
     */
    @ActivityMethod(name = "Sign")
    SignOutcome sign(String unsignedServiceRequest, String deviceType);
}
