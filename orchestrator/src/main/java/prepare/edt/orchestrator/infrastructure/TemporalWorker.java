package prepare.edt.orchestrator.infrastructure;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;
import prepare.edt.orchestrator.adapter.BatchWorkflow;
import prepare.edt.orchestrator.adapter.BatchWorkflowImpl;
import prepare.edt.orchestrator.adapter.DspSignatureVerifier;
import prepare.edt.orchestrator.adapter.DuisSenderActivityImpl;
import prepare.edt.orchestrator.adapter.ManifestReaderActivityImpl;
import prepare.edt.orchestrator.adapter.PageWorkflowImpl;
import prepare.edt.orchestrator.adapter.PlaceholderFlowWorkflowImpl;
import prepare.edt.orchestrator.adapter.PlaceholderResponseDecoder;
import prepare.edt.orchestrator.adapter.RequestBuilderActivityImpl;
import prepare.edt.orchestrator.adapter.ResponseDecoderActivityImpl;
import prepare.edt.orchestrator.adapter.ResultPublisherActivityImpl;
import prepare.edt.orchestrator.adapter.ServiceRequestWorkflow;
import prepare.edt.orchestrator.adapter.ServiceRequestWorkflowImpl;
import prepare.edt.orchestrator.adapter.SignProbeWorkflow;
import prepare.edt.orchestrator.adapter.SignProbeWorkflowImpl;

/**
 * Starts the Orchestrator's Temporal workers when the application starts, and stops them at shutdown.
 *
 * Two workers, one per task queue:
 *   "orchestrator" - the workflows (Batch, Page, Flow, ServiceRequest, SignProbe) and the Manifest Reader,
 *                    Request Builder and Response Decoder activities
 *   "duis-send"    - the DUIS Sender activity, shared by all Service Requests, rate-limited by the server
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
    private final DspSignatureVerifier dspSignatureVerifier;
    private final BatchConfig batch;
    private final ManifestReaderActivityImpl manifestReader;
    private final ResultPublisherActivityImpl resultPublisher;
    private WorkerFactory factory;

    TemporalWorker(WorkflowClient client, TemporalConfig config, ServiceRequestConfig serviceRequest,
                   DspSignatureVerifier dspSignatureVerifier, BatchConfig batch,
                   ManifestReaderActivityImpl manifestReader, ResultPublisherActivityImpl resultPublisher) {
        this.client = client;
        this.config = config;
        this.serviceRequest = serviceRequest;
        this.dspSignatureVerifier = dspSignatureVerifier;
        this.batch = batch;
        this.manifestReader = manifestReader;
        this.resultPublisher = resultPublisher;
    }

    void onStart(@Observes StartupEvent event) {
        factory = WorkerFactory.newInstance(client);

        String signingQueue = config.signingTaskQueue();
        String sendQueue = config.sendTaskQueue();
        Duration responseTimeout = serviceRequest.responseTimeout();

        Worker orchestrator = factory.newWorker(config.taskQueue());
        var batchSettings = new BatchWorkflowImpl.BatchSettings(batch.rowsPerPage(), batch.pagesAtATime(),
                batch.pagesPerRun(), batch.flowsPerPage());
        orchestrator.registerWorkflowImplementationFactory(BatchWorkflow.class,
                () -> new BatchWorkflowImpl(batchSettings));
        orchestrator.registerWorkflowImplementationTypes(PageWorkflowImpl.class,
                // PLACEHOLDER Flow: one Service Request per device, until the Flow Catalogue (model 010.020).
                PlaceholderFlowWorkflowImpl.class);
        orchestrator.registerWorkflowImplementationFactory(SignProbeWorkflow.class,
                () -> new SignProbeWorkflowImpl(signingQueue));
        orchestrator.registerWorkflowImplementationFactory(ServiceRequestWorkflow.class,
                () -> new ServiceRequestWorkflowImpl(signingQueue, sendQueue, responseTimeout));
        // PLACEHOLDER decoder: Parsec (CSW software) goes behind the same ResponseDecoder port.
        orchestrator.registerActivitiesImplementations(manifestReader, resultPublisher, new RequestBuilderActivityImpl(),
                new ResponseDecoderActivityImpl(new PlaceholderResponseDecoder()));

        // setMaxTaskQueueActivitiesPerSecond: a limit for the whole task queue, enforced by the Temporal Server,
        // so it holds across all replicas. (setMaxWorkerActivitiesPerSecond would limit each worker alone.)
        // Every replica must use the same value.
        double sendRate = serviceRequest.sendRatePerSecond();
        Worker sender = factory.newWorker(sendQueue, WorkerOptions.newBuilder()
                .setMaxTaskQueueActivitiesPerSecond(sendRate)
                .build());
        sender.registerActivitiesImplementations(new DuisSenderActivityImpl(serviceRequest.gatewayUrl(), dspSignatureVerifier));

        factory.start();

        try (var fields = LogFields.of(
                "event.action", "worker-started",
                "temporal.target", config.target(),
                "temporal.namespace", config.namespace(),
                "temporal.task_queue", config.taskQueue() + "," + sendQueue,
                "edt.gateway_url", String.valueOf(serviceRequest.gatewayUrl()),
                "edt.response_timeout", String.valueOf(responseTimeout),
                "edt.send_rate_per_second", String.valueOf(sendRate),
                "edt.batch", batchSettings.toString())) {
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
