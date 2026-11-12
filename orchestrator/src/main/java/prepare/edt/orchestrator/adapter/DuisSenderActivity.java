package prepare.edt.orchestrator.adapter;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * The DUIS Sender: the only place a Service Request leaves the EDT.
 * Runs on its own task queue (rate-limited by the Temporal Server in step 12d).
 * Posts the signed Service Request once to the DSP HTTPS Gateway and returns the synchronous DUIS Response.
 * No retry of its own: the gateway retries transient errors (architecture model).
 * Implementation in step 12b, with the DSP simulator.
 */
@ActivityInterface
public interface DuisSenderActivity {

    @ActivityMethod(name = "SendServiceRequest")
    SyncResponse send(String signedServiceRequest);
}
