package prepare.edt.orchestrator.infrastructure;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;
import prepare.edt.orchestrator.adapter.DuisSenderActivityImpl;
import prepare.edt.orchestrator.adapter.RequestBuilderActivityImpl;
import prepare.edt.orchestrator.adapter.ServiceRequestWorkflow;
import prepare.edt.orchestrator.adapter.ServiceRequestWorkflowImpl;
import prepare.edt.orchestrator.adapter.SignProbeWorkflow;
import prepare.edt.orchestrator.adapter.SignProbeWorkflowImpl;

/**
 * Starts the Orchestrator's Temporal workers when the application starts, and stops them at shutdown.
 *
 * Two workers, one per task queue:
 *   "orchestrator" - the workflows (SignProbe, ServiceRequest) and the Request Builder activity
 *   "duis-send"    - the DUIS Sender activity, shared by all Service Requests (rate limit in a later step)
 *
 * Temporal creates the workflow objects itself, so CDI cannot inject into them:
 * the factories below pass the configuration in instead.
 */
@ApplicationScoped
public class TemporalWorker {

    private static final Logger LOG = Logger.getLogger(TemporalWorker.class);

    private final WorkflowClient client;
    private final TemporalConfig config;
    private final ServiceRequestConfig serviceRequest;
    private WorkerFactory factory;

    TemporalWorker(WorkflowClient client, TemporalConfig config, ServiceRequestConfig serviceRequest) {
        this.client = client;
        this.config = config;
        this.serviceRequest = serviceRequest;
    }

    void onStart(@Observes StartupEvent event) {
        factory = WorkerFactory.newInstance(client);

        String signingQueue = config.signingTaskQueue();
        String sendQueue = config.sendTaskQueue();
        Duration responseTimeout = serviceRequest.responseTimeout();

        Worker orchestrator = factory.newWorker(config.taskQueue());
        orchestrator.registerWorkflowImplementationFactory(SignProbeWorkflow.class,
                () -> new SignProbeWorkflowImpl(signingQueue));
        orchestrator.registerWorkflowImplementationFactory(ServiceRequestWorkflow.class,
                () -> new ServiceRequestWorkflowImpl(signingQueue, sendQueue, responseTimeout));
        orchestrator.registerActivitiesImplementations(new RequestBuilderActivityImpl());

        Worker sender = factory.newWorker(sendQueue);
        sender.registerActivitiesImplementations(new DuisSenderActivityImpl(serviceRequest.gatewayUrl()));

        factory.start();

        try (var fields = LogFields.of(
                "event.action", "worker-started",
                "temporal.target", config.target(),
                "temporal.namespace", config.namespace(),
                "temporal.task_queue", config.taskQueue() + "," + sendQueue,
                "edt.gateway_url", String.valueOf(serviceRequest.gatewayUrl()),
                "edt.response_timeout", String.valueOf(responseTimeout))) {
            LOG.info("Temporal workers started");
        }
    }

    void onStop(@Observes ShutdownEvent event) throws InterruptedException {
        if (factory != null) {
            factory.shutdown();
            factory.awaitTermination(10, TimeUnit.SECONDS);
            LOG.info("Temporal workers stopped");
        }
    }
}
