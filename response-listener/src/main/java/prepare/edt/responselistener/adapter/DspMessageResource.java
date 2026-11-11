package prepare.edt.responselistener.adapter;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import prepare.edt.responselistener.infrastructure.LogFields;
import prepare.edt.responselistener.usecase.DspMessageReader.UnreadableMessageException;
import prepare.edt.responselistener.usecase.RouteDspMessage;
import prepare.edt.responselistener.usecase.WorkflowSignaller.SignallerUnavailableException;

/**
 * POST /duis/responses: where the DSP HTTPS Gateway delivers Responses and DCC Alerts.
 *
 * The HTTP status tells the gateway whether to try again:
 *   200  delivered to the workflow, or no workflow waits for it (orphan: retrying would not help)
 *   400  unreadable message (retrying would not help either)
 *   503  Temporal unreachable: the gateway should retry later
 */
@Path("/duis/responses")
public class DspMessageResource {

    private static final Logger LOG = Logger.getLogger(DspMessageResource.class);

    private final RouteDspMessage routeDspMessage;

    public DspMessageResource(RouteDspMessage routeDspMessage) {
        this.routeDspMessage = routeDspMessage;
    }

    @POST
    @Consumes({MediaType.APPLICATION_XML, MediaType.TEXT_XML})
    @Produces(MediaType.TEXT_PLAIN)
    public Response receive(String body) {
        try {
            RouteDspMessage.Result result = routeDspMessage.route(body);
            boolean signalled = result instanceof RouteDspMessage.Result.Signalled;
            try (var fields = LogFields.of(
                    "event.action", signalled ? "dsp-message-signalled" : "dsp-message-orphan",
                    "edt.request_id", result.message().requestId(),
                    "edt.message_type", result.message().kind().name(),
                    "edt.alert_code", String.valueOf(result.message().alertCode()),
                    "temporal.workflow_id", result.workflowId())) {
                if (signalled) {
                    LOG.info("DSP message delivered to its Service Request workflow");
                } else {
                    LOG.warn("No running Service Request workflow for this DSP message");
                }
            }
            return Response.ok(signalled ? "signalled" : "orphan").build();
        } catch (UnreadableMessageException e) {
            try (var fields = LogFields.of("event.action", "dsp-message-unreadable", "error.message", e.getMessage())) {
                LOG.warn("Unreadable DSP message");
            }
            return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (SignallerUnavailableException e) {
            try (var fields = LogFields.of("event.action", "dsp-message-not-delivered", "error.message", e.getMessage())) {
                LOG.error("Could not signal the workflow; the gateway should retry");
            }
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity("try again later").build();
        }
    }
}
