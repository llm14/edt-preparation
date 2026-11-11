package prepare.edt.responselistener.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import prepare.edt.responselistener.entity.DspMessage;

/** The use case with fake ports: no XML, no Temporal. */
class RouteDspMessageTest {

    static final DspMessage RESPONSE = new DspMessage(DspMessage.Kind.RESPONSE, "orig:dev:1", null, "<Response/>");

    final List<String> signalledIds = new ArrayList<>();

    @Test
    void signals_the_workflow_derived_from_the_request_id() {
        var route = new RouteDspMessage(body -> RESPONSE, signaller(true));

        var result = route.route("<Response/>");

        assertInstanceOf(RouteDspMessage.Result.Signalled.class, result);
        assertEquals(List.of("service-request-orig:dev:1"), signalledIds);
    }

    @Test
    void no_running_workflow_is_an_orphan() {
        var route = new RouteDspMessage(body -> RESPONSE, signaller(false));

        assertInstanceOf(RouteDspMessage.Result.Orphan.class, route.route("<Response/>"));
    }

    @Test
    void an_unreadable_message_is_never_signalled() {
        DspMessageReader failing = body -> {
            throw new DspMessageReader.UnreadableMessageException("bad", null);
        };
        var route = new RouteDspMessage(failing, signaller(true));

        assertThrows(DspMessageReader.UnreadableMessageException.class, () -> route.route("junk"));
        assertEquals(List.of(), signalledIds);
    }

    WorkflowSignaller signaller(boolean running) {
        return (workflowId, message) -> {
            signalledIds.add(workflowId);
            return running;
        };
    }
}
