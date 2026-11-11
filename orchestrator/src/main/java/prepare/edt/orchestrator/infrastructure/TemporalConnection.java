package prepare.edt.orchestrator.infrastructure;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Creates the two Temporal objects the rest of the application injects.
 *
 * WorkflowServiceStubs: the gRPC connection to the Temporal Server. It connects lazily,
 * so the application starts even if Temporal is down (readiness then reports DOWN).
 * WorkflowClient: the API on top of it, bound to one namespace.
 *
 * A CDI producer method (@Produces) is how we create objects from libraries that have no CDI annotations.
 */
@ApplicationScoped
public class TemporalConnection {

    @Produces
    @Singleton
    WorkflowServiceStubs serviceStubs(TemporalConfig config) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(config.target())
                        .build());
    }

    @Produces
    @Singleton
    WorkflowClient workflowClient(WorkflowServiceStubs serviceStubs, TemporalConfig config) {
        return WorkflowClient.newInstance(serviceStubs,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(config.namespace())
                        .build());
    }

    /** Called by CDI at shutdown: closes the gRPC connection. */
    void close(@Disposes WorkflowServiceStubs serviceStubs) {
        serviceStubs.shutdown();
    }
}
