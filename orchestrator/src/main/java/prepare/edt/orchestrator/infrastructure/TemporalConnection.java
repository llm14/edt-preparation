package prepare.edt.orchestrator.infrastructure;

import com.uber.m3.tally.RootScopeBuilder;
import com.uber.m3.tally.Scope;
import com.uber.m3.tally.ScopeCloseException;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.common.reporter.MicrometerClientStatsReporter;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Creates the Temporal objects the rest of the application injects.
 *
 * WorkflowServiceStubs: the gRPC connection to the Temporal Server. It connects lazily,
 * so the application starts even if Temporal is down (readiness then reports DOWN).
 * WorkflowClient: the API on top of it, bound to one namespace.
 * Scope: where the Temporal SDK writes its metrics (requests, latencies, task polls, schedule-to-start...).
 * The SDK uses Tally for metrics; MicrometerClientStatsReporter passes them on to Quarkus' Micrometer registry,
 * so they appear at /q/metrics next to the JVM metrics, with the prefix "temporal_".
 *
 * A CDI producer method (@Produces) is how we create objects from libraries that have no CDI annotations.
 */
@ApplicationScoped
public class TemporalConnection {

    @Produces
    @Singleton
    Scope metricsScope(MeterRegistry registry) {
        return new RootScopeBuilder()
                .reporter(new MicrometerClientStatsReporter(registry))
                .reportEvery(com.uber.m3.util.Duration.ofSeconds(10));
    }

    @Produces
    @Singleton
    WorkflowServiceStubs serviceStubs(TemporalConfig config, Scope metricsScope) {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(config.target())
                        .setMetricsScope(metricsScope)   // workers made from this client report here too
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

    /** Called by CDI at shutdown: reports the last metrics and stops the reporting thread. */
    void close(@Disposes Scope metricsScope) throws ScopeCloseException {
        metricsScope.close();
    }
}
