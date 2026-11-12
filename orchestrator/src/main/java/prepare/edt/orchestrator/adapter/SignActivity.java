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

    @ActivityMethod(name = "Sign")
    SignOutcome sign(String unsignedServiceRequest);
}
