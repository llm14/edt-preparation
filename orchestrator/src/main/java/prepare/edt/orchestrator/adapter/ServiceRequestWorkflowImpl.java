package prepare.edt.orchestrator.adapter;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import org.slf4j.Logger;
import prepare.edt.orchestrator.entity.DccAlerts;

/**
 * The Service Request workflow (architecture model, sequence 010.030):
 *
 *   1. build the unsigned request            RequestBuilderActivity   (orchestrator queue)
 *   2. sign it                               Sign                     (DUIS Signer's queue)
 *   3. send it once                          DuisSenderActivity       (send queue, 1 attempt)
 *   4. wait for the Response or a DCC Alert  signal "DspMessage"      (up to the response timeout)
 *
 * Never sends again: refused, rejected, send failure, N12/N13 and timeout all end it as failed.
 *
 * Workflow code must be deterministic (it is replayed from history): no I/O, no system clock, no threads.
 * Waiting uses Workflow.await, which Temporal turns into a durable timer: the wait survives restarts.
 */
public class ServiceRequestWorkflowImpl implements ServiceRequestWorkflow {

    private static final Logger LOG = Workflow.getLogger(ServiceRequestWorkflowImpl.class);

    private final RequestBuilderActivity builder;
    private final SignActivity signer;
    private final DuisSenderActivity sender;
    private final Duration responseTimeout;

    /** Messages signalled by the Response Listener, in arrival order. They may arrive at any time, even before send. */
    private final Queue<DspMessage> messages = new ArrayDeque<>();

    public ServiceRequestWorkflowImpl(String signingTaskQueue, String sendTaskQueue, Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
        this.builder = Workflow.newActivityStub(RequestBuilderActivity.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build());
        this.signer = Workflow.newActivityStub(SignActivity.class, ActivityOptions.newBuilder()
                .setTaskQueue(signingTaskQueue)
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .build());
        this.sender = Workflow.newActivityStub(DuisSenderActivity.class, ActivityOptions.newBuilder()
                .setTaskQueue(sendTaskQueue)
                .setStartToCloseTimeout(Duration.ofSeconds(60))
                // Posts once: no retry of its own. The DSP HTTPS Gateway retries transient errors.
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build());
    }

    @Override
    public ServiceRequestOutcome run(ServiceRequestInput input) {
        String requestId = input.requestId();

        String unsigned = builder.build(input);

        SignOutcome signed = signer.sign(unsigned);
        if (!SignOutcome.SIGNED.equals(signed.status())) {
            return outcome(ServiceRequestOutcome.REFUSED_BY_SIGNER, requestId, null, null, signed.refusalReason());
        }

        SyncResponse syncResponse;
        try {
            syncResponse = sender.send(signed.signedServiceRequest());
        } catch (ActivityFailure e) {
            return outcome(ServiceRequestOutcome.SEND_FAILED, requestId, null, null, rootMessage(e));
        }
        if (!syncResponse.accepted()) {
            return outcome(ServiceRequestOutcome.REJECTED_BY_DSP, requestId, null, null,
                    syncResponse.responseCode() + " " + syncResponse.detail());
        }

        return waitForResponse(requestId);
    }

    /** Waits until a Response or a failing DCC Alert arrives, or the timeout ends. Other alerts are logged and skipped. */
    private ServiceRequestOutcome waitForResponse(String requestId) {
        long deadline = Workflow.currentTimeMillis() + responseTimeout.toMillis();
        while (true) {
            long remaining = deadline - Workflow.currentTimeMillis();
            boolean arrived = remaining > 0 && Workflow.await(Duration.ofMillis(remaining), () -> !messages.isEmpty());
            if (!arrived) {
                return outcome(ServiceRequestOutcome.TIMED_OUT, requestId, null, null,
                        "no Response within " + responseTimeout);
            }
            DspMessage message = messages.poll();
            if (DspMessage.RESPONSE.equals(message.type())) {
                return outcome(ServiceRequestOutcome.RESPONDED, requestId, message.xml(), null, null);
            }
            if (DccAlerts.failsServiceRequest(message.alertCode())) {
                return outcome(ServiceRequestOutcome.FAILED_ALERT, requestId, null, message.alertCode(),
                        "DCC Alert " + message.alertCode());
            }
            LOG.info("DCC Alert {} for {}: keep waiting", message.alertCode(), requestId);
        }
    }

    @Override
    public void onDspMessage(DspMessage message) {
        messages.add(message);
    }

    private static ServiceRequestOutcome outcome(String status, String requestId, String responseXml,
                                                 String alertCode, String detail) {
        LOG.info("Service Request {} ended: {}", requestId, status);
        return new ServiceRequestOutcome(status, requestId, responseXml, alertCode, detail);
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
