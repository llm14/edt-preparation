package prepare.edt.orchestrator.adapter;

import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import org.jboss.logging.Logger;
import prepare.edt.orchestrator.entity.Facts;
import prepare.edt.orchestrator.infrastructure.LogFields;
import prepare.edt.orchestrator.usecase.ResponseDecoder;

/**
 * The activity around the ResponseDecoder port. Logs each Response decoded (model: the Orchestrator logs
 * "each Response decoded"). A Response that cannot be decoded fails without retry: decoding again gives the same result.
 */
public class ResponseDecoderActivityImpl implements ResponseDecoderActivity {

    static final String NOT_DECODABLE = "ResponseNotDecodable";
    private static final Logger LOG = Logger.getLogger(ResponseDecoderActivityImpl.class);

    private final ResponseDecoder decoder;

    public ResponseDecoderActivityImpl(ResponseDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public Facts decode(String srv, String responseXml) {
        String workflowId = workflowId();
        try {
            Facts facts = decoder.decode(srv, responseXml);
            try (var fields = LogFields.of(
                    "event.action", "response-decoded",
                    "edt.srv", srv,
                    "edt.facts", String.join(",", facts.values().keySet()),
                    "temporal.workflow_id", workflowId)) {
                LOG.info("Response decoded");
            }
            return facts;
        } catch (ResponseDecoder.UndecodableResponseException e) {
            try (var fields = LogFields.of(
                    "event.action", "response-not-decodable",
                    "edt.srv", srv,
                    "error.message", e.getMessage(),
                    "temporal.workflow_id", workflowId)) {
                LOG.warn("Response could not be decoded");
            }
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), NOT_DECODABLE);
        }
    }

    /** The calling workflow's ID, for the logs; "none" when called outside Temporal (unit tests). */
    private static String workflowId() {
        try {
            return Activity.getExecutionContext().getInfo().getWorkflowId();
        } catch (RuntimeException e) {
            return "none";
        }
    }
}
