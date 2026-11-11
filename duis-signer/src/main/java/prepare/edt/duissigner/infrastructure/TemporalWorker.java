package prepare.edt.duissigner.infrastructure;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;
import prepare.edt.duissigner.adapter.SignActivityImpl;
import prepare.edt.duissigner.usecase.SignServiceRequest;

/**
 * Starts the Temporal worker when the application starts, and stops it at shutdown.
 *
 * A worker polls one task queue on the Temporal Server. When the Orchestrator schedules a Sign activity
 * on that queue, the server hands the task to one of the polling workers, which runs it and reports the result.
 */
@ApplicationScoped
public class TemporalWorker {

    private static final Logger LOG = Logger.getLogger(TemporalWorker.class);

    private final WorkflowClient client;
    private final TemporalConfig config;
    private final SignServiceRequest signServiceRequest;
    private WorkerFactory factory;

    TemporalWorker(WorkflowClient client, TemporalConfig config, SignServiceRequest signServiceRequest) {
        this.client = client;
        this.config = config;
        this.signServiceRequest = signServiceRequest;
    }

    void onStart(@Observes StartupEvent event) {
        factory = WorkerFactory.newInstance(client);
        // The rate limit is for the whole task queue and is enforced by the Temporal Server (all replicas together).
        Worker worker = factory.newWorker(config.taskQueue(), WorkerOptions.newBuilder()
                .setMaxTaskQueueActivitiesPerSecond(config.signingRatePerSecond())
                .build());
        worker.registerActivitiesImplementations(new SignActivityImpl(signServiceRequest));
        factory.start();

        try (var fields = LogFields.of(
                "event.action", "worker-started",
                "temporal.target", config.target(),
                "temporal.namespace", config.namespace(),
                "temporal.task_queue", config.taskQueue(),
                "edt.signing_rate_per_second", String.valueOf(config.signingRatePerSecond()))) {
            LOG.info("Temporal worker started");
        }
    }

    void onStop(@Observes ShutdownEvent event) throws InterruptedException {
        if (factory != null) {
            factory.shutdown();
            factory.awaitTermination(10, TimeUnit.SECONDS);
            LOG.info("Temporal worker stopped");
        }
    }
}
