package prepare.edt.orchestrator.adapter;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import prepare.edt.orchestrator.entity.Flow;

/**
 * Flow Catalogue Activity (model 010.020, `edt.orchestrator.flowCatalogue`): returns an approved Flow, or says there is none.
 *
 * It is an activity, not a lookup inside the workflow, on purpose: the result is recorded in the workflow history, so a Flow that
 * is running when the catalogue changes replays with the steps it was given. A lookup in workflow code would give another answer
 * on replay (non-determinism).
 */
@ActivityInterface
public interface FlowCatalogueActivity {

    /** The stub workflows use. Nothing outside the process is touched, so a short timeout is enough. */
    static FlowCatalogueActivity stub() {
        return Workflow.newActivityStub(FlowCatalogueActivity.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build());
    }

    @ActivityMethod(name = "LookupFlow")
    Lookup lookup(String flow);

    /**
     * @param found whether the catalogue has the Flow
     * @param flow  the Flow when found, otherwise null
     */
    record Lookup(boolean found, Flow flow) {
    }
}
