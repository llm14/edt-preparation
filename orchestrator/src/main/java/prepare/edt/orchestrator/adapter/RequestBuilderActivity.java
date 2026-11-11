package prepare.edt.orchestrator.adapter;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/** Builds the unsigned DUIS XML of a Service Request, with its Request ID (architecture model). */
@ActivityInterface
public interface RequestBuilderActivity {

    @ActivityMethod(name = "BuildServiceRequest")
    String build(ServiceRequestInput input);
}
