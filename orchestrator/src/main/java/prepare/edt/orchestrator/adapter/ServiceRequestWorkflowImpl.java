package prepare.edt.orchestrator.adapter;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.Priority;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import org.slf4j.Logger;
import prepare.edt.orchestrator.entity.DccAlerts;
import prepare.edt.orchestrator.entity.FairnessKey;
import prepare.edt.orchestrator.entity.Facts;

/**
 * The Service Request workflow (architecture model, sequence 010.030):
 *
 *   1. build the unsigned request            RequestBuilderActivity   (orchestrator queue)
 *   2. sign it                               Sign                     (DUIS Signer's queue)
 *   3. send it once                          DuisSenderActivity       (send queue, 1 attempt, fair by Batch)
 *   4. wait for the Response or a DCC Alert  signal "DspMessage"      (up to the response timeout)
 *   5. decode the Response into Facts        ResponseDecoderActivity  (orchestrator queue)
 *
 * Never sends again: refused, rejected, send failure, N12/N13 and timeout all end it as failed.
 *
 * Workflow code must be deterministic (it is replayed from history): no I/O, no system clock, no threads.
 * Waiting uses Workflow.await, which Temporal turns into a durable timer: the wait survives restarts.
 */
public class ServiceRequestWorkflowImpl implements ServiceRequestWorkflow {

    private static final Logger LOG = Workflow.getLogger(ServiceRequestWorkflowImpl.class);

    private final RequestBuilderActivity builder;
    private final ResponseDecoderActivity decoder;
    private final SignActivity signer;
    private final String sendTaskQueue;
    private final Duration responseTimeout;

    /** Messages signalled by the Response Listener, in arrival order. They may arrive at any time, even before send. */
    private final Queue<DspMessage> messages = new ArrayDeque<>();

    public ServiceRequestWorkflowImpl(String signingTaskQueue, String sendTaskQueue, Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
        this.builder = Workflow.newActivityStub(RequestBuilderActivity.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .build());
        this.decoder = Workflow.newActivityStub(ResponseDecoderActivity.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                // Retries help only with a crash of the worker; "ResponseNotDecodable" is never retried.
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .build());
        this.signer = Workflow.newActivityStub(SignActivity.class, ActivityOptions.newBuilder()
                .setTaskQueue(signingTaskQueue)
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .build());
        this.sendTaskQueue = sendTaskQueue;
    }

    /**
     * The sender stub is made per run, because its fairness key is the Batch ID from the input.
     *
     * The send queue's rate limit (set on its worker) is shared by every Batch. With a fairness key, the Temporal
     * Server keeps one virtual queue per Batch and takes from them in turn, so a large Batch does not hold up a small one.
     */
    private DuisSenderActivity sender(String batchId) {
        return Workflow.newActivityStub(DuisSenderActivity.class, ActivityOptions.newBuilder()
                .setTaskQueue(sendTaskQueue)
                // Only start-to-close: the wait in the rate-limited queue does not count against it.
                .setStartToCloseTimeout(Duration.ofSeconds(60))
                // Posts once: no retry of its own. The DSP HTTPS Gateway retries transient errors.
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .setPriority(Priority.newBuilder().setFairnessKey(FairnessKey.of(batchId)).build())
                .build());
    }

    @Override
    public ServiceRequestOutcome executeServiceRequest(ServiceRequestInput input) {
        String requestId = input.requestId();

        String unsigned = builder.build(input);

        SignOutcome signed = signer.sign(unsigned);
        if (!SignOutcome.SIGNED.equals(signed.status())) {
            return outcome(ServiceRequestOutcome.REFUSED_BY_SIGNER, requestId, null, null, signed.refusalReason());
        }

        SyncResponse syncResponse;
        try {
            syncResponse = sender(input.batchId()).send(signed.signedServiceRequest());
        } catch (ActivityFailure e) {
            return outcome(ServiceRequestOutcome.SEND_FAILED, requestId, null, null, rootMessage(e));
        }
        if (!syncResponse.accepted()) {
            return outcome(ServiceRequestOutcome.REJECTED_BY_DSP, requestId, null, null,
                    syncResponse.responseCode() + " " + syncResponse.detail());
        }

        return waitForResponse(requestId, input.srv());
    }

    /** Waits until a Response or a failing DCC Alert arrives, or the timeout ends. Other alerts are logged and skipped. */
    private ServiceRequestOutcome waitForResponse(String requestId, String srv) {
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
                return decode(requestId, srv, message.xml());
            }
            if (DccAlerts.failsServiceRequest(message.alertCode())) {
                return outcome(ServiceRequestOutcome.FAILED_ALERT, requestId, null, message.alertCode(),
                        "DCC Alert " + message.alertCode());
            }
            LOG.info("DCC Alert {} for {}: keep waiting", message.alertCode(), requestId);
        }
    }

    /** The Response arrived: decode it into Facts (model 010.030, steps 20 and 21). */
    private ServiceRequestOutcome decode(String requestId, String srv, String responseXml) {
        try {
            Facts facts = decoder.decode(srv, responseXml);
            return outcome(ServiceRequestOutcome.RESPONDED, requestId, facts, null, null);
        } catch (ActivityFailure e) {
            return outcome(ServiceRequestOutcome.DECODE_FAILED, requestId, null, null, rootMessage(e));
        }
    }

    @Override
    public void onDspMessage(DspMessage message) {
        messages.add(message);
    }

    private static ServiceRequestOutcome outcome(String status, String requestId, Facts facts,
                                                 String alertCode, String detail) {
        LOG.info("Service Request {} ended: {}", requestId, status);
        return new ServiceRequestOutcome(status, requestId, facts, alertCode, detail);
    }

    /** The message of the deepest cause; for an ApplicationFailure, its original text (getMessage adds the type). */
    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root instanceof ApplicationFailure failure ? failure.getOriginalMessage() : root.getMessage();
    }
}
