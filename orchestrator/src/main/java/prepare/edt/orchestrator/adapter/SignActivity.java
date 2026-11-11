package prepare.edt.orchestrator.adapter;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The Orchestrator's copy of the DUIS Signer's Sign activity (client side).
 *
 * The Orchestrator never implements it: the DUIS Signer does, in another container.
 * This copy only lets workflows call it. Temporal matches the two by the name "Sign"
 * and the task queue, so both must stay identical to the signer's side.
 *
 * Placeholder signature: a String in and out, as in the signer.
 */
@ActivityInterface
public interface SignActivity {

    @ActivityMethod(name = "Sign")
    String sign(String unsignedServiceRequest);
}
