package prepare.edt.responselistener.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import prepare.edt.responselistener.usecase.RouteDspMessage;
import prepare.edt.responselistener.usecase.WorkflowSignaller;

/** The HTTP answer for each outcome: it tells the gateway whether to retry. */
class DspMessageResourceTest {

    static final String RESPONSE = "<Response xmlns=\"urn:edt:placeholder:duis\"><RequestId>o:d:1</RequestId></Response>";

    @Test
    void signalled_is_200() {
        assertEquals(200, resource((id, m) -> true).receive(RESPONSE).getStatus());
    }

    @Test
    void orphan_is_200_because_a_retry_would_not_help() {
        var answer = resource((id, m) -> false).receive(RESPONSE);

        assertEquals(200, answer.getStatus());
        assertEquals("orphan", answer.getEntity());
    }

    @Test
    void unreadable_is_400() {
        assertEquals(400, resource((id, m) -> true).receive("not xml").getStatus());
    }

    @Test
    void temporal_unreachable_is_503_so_the_gateway_retries() {
        WorkflowSignaller down = (id, m) -> {
            throw new WorkflowSignaller.SignallerUnavailableException("UNAVAILABLE", null);
        };

        assertEquals(503, resource(down).receive(RESPONSE).getStatus());
    }

    static DspMessageResource resource(WorkflowSignaller signaller) {
        return new DspMessageResource(new RouteDspMessage(new XmlDspMessageReader(), signaller));
    }
}
