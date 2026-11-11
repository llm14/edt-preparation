package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.activity.Activity;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.Map;
import prepare.edt.orchestrator.entity.Facts;
import prepare.edt.orchestrator.entity.RequestId;

/**
 * The Service Request workflow on an in-memory Temporal server (no Docker). Fakes for the signer and the sender;
 * the real Request Builder. Temporal's test server skips time, so a 2-minute timeout passes instantly.
 * Signals are sent the way the Response Listener will: to the workflow ID derived from the Request ID.
 */
@Timeout(20)
class ServiceRequestWorkflowTest {

    static final String ORCHESTRATOR_QUEUE = "orchestrator";
    static final String SIGNING_QUEUE = "duis-signing";
    static final String SEND_QUEUE = "duis-send";
    static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(2);

    static final ServiceRequestInput INPUT = new ServiceRequestInput(
            RequestId.placeholder("90-B3-D5-1F-30-01-00-01", "00-DB-12-34-56-78-90-A0", 1_000L).value(), "batch-1",
            "90-B3-D5-1F-30-01-00-01", "00-DB-12-34-56-78-90-A0", "ESME", "4.1.1");

    /** Responses in the simulator's placeholder format, which the PlaceholderResponseDecoder reads. */
    static final String RESPONSE_XML = response("12345.6");
    static final String EARLY_RESPONSE_XML = response("7.0");

    static String response(String reading) {
        return "<Response xmlns=\"urn:edt:placeholder:duis\"><RequestId>R</RequestId><ResponseCode>I0</ResponseCode>"
                + "<Body><InstantaneousImportRegister>" + reading + "</InstantaneousImportRegister></Body></Response>";
    }

    TestWorkflowEnvironment env;
    Worker signerWorker;
    Worker senderWorker;
    final List<String> sent = new ArrayList<>();
    final AtomicInteger sends = new AtomicInteger();
    final List<String> fairnessKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker orchestrator = env.newWorker(ORCHESTRATOR_QUEUE);
        orchestrator.registerWorkflowImplementationFactory(ServiceRequestWorkflow.class,
                () -> new ServiceRequestWorkflowImpl(SIGNING_QUEUE, SEND_QUEUE, RESPONSE_TIMEOUT));
        orchestrator.registerActivitiesImplementations(new RequestBuilderActivityImpl(),
                new ResponseDecoderActivityImpl(new PlaceholderResponseDecoder()));
        signerWorker = env.newWorker(SIGNING_QUEUE);
        senderWorker = env.newWorker(SEND_QUEUE);
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void a_response_completes_it() {
        signerSigns();
        dspAccepts();
        env.start();

        ServiceRequestWorkflow workflow = start();
        signal(new DspMessage(DspMessage.RESPONSE, null, RESPONSE_XML));

        ServiceRequestOutcome outcome = result(workflow);
        assertEquals(ServiceRequestOutcome.RESPONDED, outcome.status());
        assertEquals(Facts.of(Map.of("InstantaneousImportRegister", "12345.6")), outcome.facts(),
                "the Response is decoded into Facts");
        assertEquals(1, sends.get());
        assertTrue(sent.get(0).contains("<RequestId>" + INPUT.requestId() + "</RequestId>"),
                "the Request Builder writes the Request ID into the request");
        assertTrue(sent.get(0).contains("<Signature/>"), "what is sent is the signed request");
    }

    @Test
    void a_response_that_cannot_be_decoded_is_decode_failed_and_not_sent_again() {
        signerSigns();
        dspAccepts();
        env.start();

        ServiceRequestWorkflow workflow = start();
        signal(new DspMessage(DspMessage.RESPONSE, null, "<Response xmlns=\"urn:edt:placeholder:duis\"/>"));

        ServiceRequestOutcome outcome = result(workflow);
        assertEquals(ServiceRequestOutcome.DECODE_FAILED, outcome.status());
        assertEquals("the Response has no Body", outcome.detail());
        assertEquals(1, sends.get());
    }

    @Test
    void the_send_is_keyed_by_batch_for_fairness() {
        signerSigns();
        dspAccepts();
        env.start();

        ServiceRequestWorkflow workflow = start();
        signal(new DspMessage(DspMessage.RESPONSE, null, RESPONSE_XML));
        result(workflow);

        assertEquals(List.of("batch-1"), fairnessKeys);
    }

    @Test
    void a_response_that_arrives_before_the_send_returns_is_not_lost() {
        signerSigns();
        // The "DSP" answers so fast that the signal arrives while the send activity is still running.
        senderWorker.registerActivitiesImplementations((DuisSenderActivity) signedXml -> {
            sends.incrementAndGet();
            signal(new DspMessage(DspMessage.RESPONSE, null, EARLY_RESPONSE_XML));
            return new SyncResponse(true, "I0", "accepted");
        });
        env.start();

        ServiceRequestOutcome outcome = result(start());

        assertEquals(ServiceRequestOutcome.RESPONDED, outcome.status());
        assertEquals("7.0", outcome.facts().values().get("InstantaneousImportRegister"));
    }

    @Test
    void dcc_alert_n12_fails_it_and_it_is_not_sent_again() {
        signerSigns();
        dspAccepts();
        env.start();

        ServiceRequestWorkflow workflow = start();
        signal(new DspMessage(DspMessage.DCC_ALERT, "N12", "<Alert/>"));

        ServiceRequestOutcome outcome = result(workflow);
        assertEquals(ServiceRequestOutcome.FAILED_ALERT, outcome.status());
        assertEquals("N12", outcome.alertCode());
        assertEquals(1, sends.get());
    }

    @Test
    void another_alert_does_not_end_the_wait() {
        signerSigns();
        dspAccepts();
        env.start();

        ServiceRequestWorkflow workflow = start();
        signal(new DspMessage(DspMessage.DCC_ALERT, "N53", "<Alert/>"));
        signal(new DspMessage(DspMessage.RESPONSE, null, RESPONSE_XML));

        assertEquals(ServiceRequestOutcome.RESPONDED, result(workflow).status());
    }

    @Test
    void no_response_times_out_and_it_is_not_sent_again() {
        signerSigns();
        dspAccepts();
        env.start();

        ServiceRequestOutcome outcome = result(start());   // the test server skips the 2 minutes

        assertEquals(ServiceRequestOutcome.TIMED_OUT, outcome.status());
        assertEquals(1, sends.get());
    }

    @Test
    void a_refused_signature_stops_it_before_sending() {
        signerWorker.registerActivitiesImplementations((SignActivity) xml ->
                new SignOutcome(SignOutcome.REFUSED, INPUT.requestId(), null, null, "SRV not allowed"));
        dspAccepts();
        env.start();

        ServiceRequestOutcome outcome = result(start());

        assertEquals(ServiceRequestOutcome.REFUSED_BY_SIGNER, outcome.status());
        assertEquals("SRV not allowed", outcome.detail());
        assertEquals(0, sends.get(), "a refused request is never sent");
    }

    @Test
    void a_rejection_in_the_synchronous_response_ends_it_without_waiting() {
        signerSigns();
        senderWorker.registerActivitiesImplementations((DuisSenderActivity) signedXml -> {
            sends.incrementAndGet();
            return new SyncResponse(false, "E99", "rejected by the DSP");
        });
        env.start();

        ServiceRequestOutcome outcome = result(start());

        assertEquals(ServiceRequestOutcome.REJECTED_BY_DSP, outcome.status());
        assertEquals(1, sends.get());
    }

    @Test
    void a_failed_post_is_not_retried() {
        signerSigns();
        senderWorker.registerActivitiesImplementations((DuisSenderActivity) signedXml -> {
            sends.incrementAndGet();
            throw new IllegalStateException("gateway unreachable");
        });
        env.start();

        ServiceRequestOutcome outcome = result(start());

        assertEquals(ServiceRequestOutcome.SEND_FAILED, outcome.status());
        assertEquals(1, sends.get(), "the sender posts once: no retry of its own");
    }

    // --- helpers ---

    void signerSigns() {
        signerWorker.registerActivitiesImplementations((SignActivity) xml ->
                new SignOutcome(SignOutcome.SIGNED, INPUT.requestId(), "OTHER_USER",
                        xml.replace("</ServiceRequest>", "<Signature/></ServiceRequest>"), null));
    }

    void dspAccepts() {
        senderWorker.registerActivitiesImplementations((DuisSenderActivity) signedXml -> {
            sends.incrementAndGet();
            sent.add(signedXml);
            fairnessKeys.add(Activity.getExecutionContext().getInfo().getPriority().getFairnessKey());
            return new SyncResponse(true, "I0", "accepted");
        });
    }

    /** Starts the workflow with its ID derived from the Request ID, as the Flow will. */
    ServiceRequestWorkflow start() {
        ServiceRequestWorkflow workflow = env.getWorkflowClient().newWorkflowStub(ServiceRequestWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(ORCHESTRATOR_QUEUE)
                        .setWorkflowId(RequestId.workflowId(INPUT.requestId()))
                        .build());
        WorkflowClient.start(workflow::run, INPUT);
        return workflow;
    }

    /** Signals like the Response Listener: knows only the Request ID, derives the workflow ID. */
    void signal(DspMessage message) {
        env.getWorkflowClient()
                .newWorkflowStub(ServiceRequestWorkflow.class, RequestId.workflowId(INPUT.requestId()))
                .onDspMessage(message);
    }

    /** Waits for the workflow's result (the test server skips time while waiting). */
    ServiceRequestOutcome result(ServiceRequestWorkflow workflow) {
        return WorkflowStub.fromTyped(workflow).getResult(ServiceRequestOutcome.class);
    }
}
