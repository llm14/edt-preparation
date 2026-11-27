package prepare.edt.responselistener.adapter;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prepare.edt.responselistener.infrastructure.InterfaceLog;
import prepare.edt.responselistener.infrastructure.LogFields;
import prepare.edt.responselistener.usecase.DspMessageReader.UnreadableMessageException;
import prepare.edt.responselistener.usecase.DspSignatureCheck.InvalidDspSignatureException;
import prepare.edt.responselistener.usecase.RouteDspMessage;
import prepare.edt.responselistener.usecase.WorkflowSignaller.SignallerUnavailableException;

/**
 * POST /duis/responses: where the DSP HTTPS Gateway delivers Responses and DCC Alerts.
 *
 * The HTTP status tells the gateway whether to try again:
 *   200  delivered to the workflow, or no workflow waits for it (orphan: retrying would not help)
 *   400  bad DSP signature (discarded and logged as a security event), or unreadable message:
 *        retrying would not help
 *   500  Temporal unreachable: the gateway delivers it again later (architecture model, Response Endpoint)
 */
@Path("/duis/responses")
public class DspMessageResource {

    private static final Logger LOG = LoggerFactory.getLogger(DspMessageResource.class);
    private static final Logger SECURITY = LoggerFactory.getLogger("edt.security");

    private final RouteDspMessage routeDspMessage;

    public DspMessageResource(RouteDspMessage routeDspMessage) {
        this.routeDspMessage = routeDspMessage;
    }

    @POST
    @Consumes({MediaType.APPLICATION_XML, MediaType.TEXT_XML})
    @Produces(MediaType.TEXT_PLAIN)
    public Response receive(String body) {
        long startedAt = System.nanoTime();
        try {
            RouteDspMessage.Result result = routeDspMessage.route(body);
            boolean signalled = result instanceof RouteDspMessage.Result.Signalled;
            // Row 16: one line for the message handled, which includes the signal to Temporal. Made after the routing,
            // not before it, so it never overlaps the lines of the failure branches below (they set event.action too).
            try (var call = InterfaceLog.inbound("dcc.dspGatewayReqHttp", signalled ? "dsp-message-signalled" : "dsp-message-orphan")
                    .duration(System.nanoTime() - startedAt)
                    .with("edt.request_id", result.message().requestId())
                    .with("edt.message_type", result.message().kind().name())
                    .with("temporal.workflow_id", result.workflowId())) {
                if (result.message().alertCode() != null) {
                    call.with("edt.alert_code", result.message().alertCode());   // a Response has none: no "null" text
                }
                call.log(signalled ? "DSP message delivered to its Service Request workflow"
                        : "No running Service Request workflow for this DSP message");
            }
            return Response.ok(signalled ? "signalled" : "orphan").build();
        } catch (InvalidDspSignatureException e) {
            // Nothing from the message is logged: it is not trusted, not even its Request ID.
            try (var fields = LogFields.of(
                    "event.kind", "alert",
                    "event.category", "intrusion_detection",
                    "event.action", "dsp-signature-invalid",
                    "event.outcome", "failure",
                    "error.message", e.getMessage(),
                    "http.request.body.bytes", String.valueOf(body == null ? 0 : body.length()))) {
                SECURITY.warn("DSP message discarded: its DSP signature is not valid");
            }
            return Response.status(Response.Status.BAD_REQUEST).entity("rejected").build();
        } catch (UnreadableMessageException e) {
            try (var fields = LogFields.of("event.action", "dsp-message-unreadable", "error.message", e.getMessage())) {
                LOG.warn("Unreadable DSP message");
            }
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (SignallerUnavailableException e) {
            // A technical failure of ours (Temporal unreachable), not outside input: the exception is passed.
            try (var fields = LogFields.of("event.action", "dsp-message-not-delivered")) {
                LOG.error("Could not signal the workflow; the gateway should retry", e);
            }
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity("try again later").build();
        }
    }
}
