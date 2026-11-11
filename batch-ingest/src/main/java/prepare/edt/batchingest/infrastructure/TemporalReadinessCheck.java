package prepare.edt.batchingest.infrastructure;

import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness: Batch Ingest can only start Batches if it can reach the Temporal Server.
 * Shown at /q/health/ready. If Temporal is down, this reports DOWN and the pod gets no work.
 */
@Readiness
@ApplicationScoped
public class TemporalReadinessCheck implements HealthCheck {

    private final WorkflowServiceStubs serviceStubs;
    private final TemporalConfig config;

    TemporalReadinessCheck(WorkflowServiceStubs serviceStubs, TemporalConfig config) {
        this.serviceStubs = serviceStubs;
        this.config = config;
    }

    @Override
    public HealthCheckResponse call() {
        var response = HealthCheckResponse.named("temporal").withData("target", config.target());
        try {
            serviceStubs.healthCheck();
            return response.up().build();
        } catch (RuntimeException e) {
            return response.down().withData("error", String.valueOf(e.getMessage())).build();
        }
    }
}
