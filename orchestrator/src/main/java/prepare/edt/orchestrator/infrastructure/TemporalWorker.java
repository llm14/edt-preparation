package prepare.edt.orchestrator.infrastructure;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;
import prepare.edt.orchestrator.adapter.SignProbeWorkflow;
import prepare.edt.orchestrator.adapter.SignProbeWorkflowImpl;

/**
 * Starts the Temporal worker when the application starts, and stops it at shutdown.
 *
 * The Orchestrator's worker runs workflows. Temporal creates the workflow objects itself,
 * so CDI cannot inject into them: the factory below passes the configuration in instead.
 */
@ApplicationScoped
public class TemporalWorker {

    private static final Logger LOG = Logger.getLogger(TemporalWorker.class);

    private final WorkflowClient client;
    private final TemporalConfig config;
    private WorkerFactory factory;

    TemporalWorker(WorkflowClient client, TemporalConfig config) {
        this.client = client;
        this.config = config;
    }

    void onStart(@Observes StartupEvent event) {
        factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(config.taskQueue());

        String signingTaskQueue = config.signingTaskQueue();
        worker.registerWorkflowImplementationFactory(SignProbeWorkflow.class,
                () -> new SignProbeWorkflowImpl(signingTaskQueue));

        factory.start();

        try (var fields = LogFields.of(
                "event.action", "worker-started",
                "temporal.target", config.target(),
                "temporal.namespace", config.namespace(),
                "temporal.task_queue", config.taskQueue())) {
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
