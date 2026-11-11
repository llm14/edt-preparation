package prepare.edt.duissigner.adapter;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The Sign activity: the only entry point of the DUIS Signer.
 * The Orchestrator calls it through Temporal, on the signing task queue.
 *
 * The name "Sign" is set explicitly: Temporal matches activities by name, and the Orchestrator
 * keeps its own copy of this interface, so the name must never change by accident (for example a rename).
 *
 * Placeholder signature: a String in and out. The real request and result types come with the signing logic.
 */
@ActivityInterface
public interface SignActivity {

    @ActivityMethod(name = "Sign")
    String sign(String unsignedServiceRequest);
}
