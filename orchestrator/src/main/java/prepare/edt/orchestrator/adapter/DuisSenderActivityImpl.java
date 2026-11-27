package prepare.edt.orchestrator.adapter;

import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import prepare.edt.orchestrator.entity.RequestId;
import prepare.edt.orchestrator.infrastructure.InterfaceLog;
import prepare.edt.orchestrator.infrastructure.LogFields;

/**
 * Posts the signed Service Request once to the DSP HTTPS Gateway (plain HTTP, the gateway does the DCCKI TLS)
 * and reads the synchronous DUIS response.
 *
 * An HTTP error or a network failure throws: with one attempt, the workflow records SEND_FAILED.
 * PLACEHOLDER format: response codes starting with "I" mean accepted (I0), others rejected.
 *
 * The synchronous response is trusted only after its DSP signature is checked (model: "returns the synchronous
 * DUIS Response after checking its DSP signature"). If the check fails, the response is not read at all:
 * a security event is logged and the activity fails with a non-retryable "DspSignatureInvalid" failure.
 * The Service Request is not sent again (the DSP may have received it), so the workflow records SEND_FAILED.
 */
public final class DuisSenderActivityImpl implements DuisSenderActivity {

    private static final String NAMESPACE = "urn:edt:placeholder:duis";
    static final String SIGNATURE_INVALID = "DspSignatureInvalid";
    private static final Logger SECURITY = LoggerFactory.getLogger("edt.security");

    private final URI gateway;
    private final HttpClient http;
    private final DspSignatureVerifier verifier;

    public DuisSenderActivityImpl(URI gateway, DspSignatureVerifier verifier) {
        this.gateway = gateway;
        this.verifier = verifier;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public SyncResponse send(String signedServiceRequest) {
        return send(signedServiceRequest, requestIdFromWorkflow());
    }

    /**
     * The activity receives only the signed XML, so the Request ID for the log comes from the workflow ID
     * ({@code service-request-<Request ID>}, the rule of {@link RequestId#workflowId(String)}).
     */
    private static String requestIdFromWorkflow() {
        try {
            return RequestId.requestIdOf(Activity.getExecutionContext().getInfo().getWorkflowId());
        } catch (RuntimeException e) {
            return "none"; // called outside Temporal (unit tests)
        }
    }

    SyncResponse send(String signedServiceRequest, String requestId) {
        HttpResponse<String> response;
        // Row 9: one line for each attempt, success or failure. No stack: Temporal logs the failed activity with its stack.
        try (var call = InterfaceLog.outbound("edt.orchestratorReqHttp", "service-request-sent")
                .with("destination.address", gateway.getHost())
                .with("http.request.body.bytes", signedServiceRequest.getBytes(StandardCharsets.UTF_8).length)
                .with("edt.request_id", requestId)) {
            try {
                response = http.send(HttpRequest.newBuilder(gateway)
                                .timeout(Duration.ofSeconds(30))
                                .header("Content-Type", "application/xml")
                                .POST(HttpRequest.BodyPublishers.ofString(signedServiceRequest))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                call.failure().log("Service Request not posted to the DSP HTTPS Gateway");
                throw new IllegalStateException("interrupted while posting to " + gateway, e);
            } catch (Exception e) {
                call.failure().log("Service Request not posted to the DSP HTTPS Gateway");
                throw new IllegalStateException("cannot post to " + gateway + ": " + e.getMessage(), e);
            }
            call.with("http.response.status_code", response.statusCode());
            if (response.statusCode() != 200) {
                call.failure();
            }
            call.log("Service Request posted to the DSP HTTPS Gateway");
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("DSP HTTPS Gateway answered HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        DspSignatureVerifier.Verdict verdict = verifier.check(response.body());
        if (!verdict.valid()) {
            try (var fields = LogFields.of(
                    "event.kind", "alert",
                    "event.category", "intrusion_detection",
                    "event.action", "dsp-signature-invalid",
                    "event.outcome", "failure",
                    "edt.message_type", "SYNC_RESPONSE",
                    "error.message", verdict.reason())) {
                SECURITY.warn("Synchronous DUIS response discarded: its DSP signature is not valid");
            }
            throw ApplicationFailure.newNonRetryableFailure(
                    "DSP signature on the synchronous response is not valid: " + verdict.reason(), SIGNATURE_INVALID);
        }
        return read(response.body());
    }

    static SyncResponse read(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            String code = text(document, "ResponseCode");
            return new SyncResponse(code.startsWith("I"), code, text(document, "Detail"));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the synchronous DUIS response: " + e.getMessage(), e);
        }
    }

    private static String text(Document document, String name) {
        var nodes = document.getElementsByTagNameNS(NAMESPACE, name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
