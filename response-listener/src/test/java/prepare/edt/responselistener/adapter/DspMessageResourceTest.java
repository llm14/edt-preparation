package prepare.edt.responselistener.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import prepare.edt.responselistener.usecase.DspSignatureCheck;
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
    void a_bad_dsp_signature_is_400_and_never_signalled() {
        var signalled = new java.util.concurrent.atomic.AtomicBoolean();
        DspSignatureCheck invalid = body -> {
            throw new DspSignatureCheck.InvalidDspSignatureException("signature does not match");
        };
        var resource = new DspMessageResource(new RouteDspMessage(invalid, new XmlDspMessageReader(),
                (id, m) -> signalled.getAndSet(true)));

        assertEquals(400, resource.receive(RESPONSE).getStatus());
        assertEquals(false, signalled.get());
    }

    @Test
    void temporal_unreachable_is_500_so_the_gateway_retries() {
        WorkflowSignaller down = (id, m) -> {
            throw new WorkflowSignaller.SignallerUnavailableException("UNAVAILABLE", null);
        };

        assertEquals(500, resource(down).receive(RESPONSE).getStatus());
    }

    @Test
    void a_signalled_message_is_one_inbound_interaction_line_with_the_ids_and_a_duration_and_no_xml() {
        try (LogCapture log = new LogCapture()) {
            resource((id, m) -> true).receive(RESPONSE);

            var lines = log.lines("edt.interaction");
            assertEquals(1, lines.size());
            assertInteraction(lines.get(0), "dsp-message-signalled");
        }
    }

    @Test
    void an_orphan_message_is_one_interaction_line_too() {
        try (LogCapture log = new LogCapture()) {
            resource((id, m) -> false).receive(RESPONSE);

            var lines = log.lines("edt.interaction");
            assertEquals(1, lines.size());
            assertInteraction(lines.get(0), "dsp-message-orphan");
        }
    }

    @Test
    void a_failed_signal_passes_the_exception_and_sets_no_error_fields_by_hand() {
        WorkflowSignaller down = (id, m) -> {
            throw new WorkflowSignaller.SignallerUnavailableException("UNAVAILABLE", new RuntimeException("cause"));
        };
        try (LogCapture log = new LogCapture()) {
            resource(down).receive(RESPONSE);

            var line = log.lines(DspMessageResource.class).get(0);
            assertEquals("dsp-message-not-delivered", line.fields().get("event.action"));
            assertEquals("UNAVAILABLE", line.thrown().getMessage());
            assertFalse(line.fields().containsKey("error.message"));
            assertFalse(line.fields().containsKey("error.type"));
        }
    }

    @Test
    void an_invalid_dsp_signature_keeps_error_message_as_a_field_and_has_no_exception() {
        DspSignatureCheck invalid = body -> {
            throw new DspSignatureCheck.InvalidDspSignatureException("signature does not match");
        };
        var resource = new DspMessageResource(new RouteDspMessage(invalid, new XmlDspMessageReader(), (id, m) -> true));
        try (LogCapture log = new LogCapture()) {
            resource.receive(RESPONSE);

            var line = log.lines("edt.security").get(0);
            assertEquals("signature does not match", line.fields().get("error.message"));
            assertEquals(null, line.thrown());
        }
    }

    @Test
    void an_unreadable_message_keeps_error_message_as_a_field_and_has_no_exception() {
        try (LogCapture log = new LogCapture()) {
            resource((id, m) -> true).receive("not xml");

            var line = log.lines(DspMessageResource.class).get(0);
            assertEquals("dsp-message-unreadable", line.fields().get("event.action"));
            assertTrue(line.fields().containsKey("error.message"));
            assertEquals(null, line.thrown());
        }
    }

    @Test
    void a_dcc_alert_line_carries_its_alert_code() {
        String alert = "<DCCAlert xmlns=\"urn:edt:placeholder:duis\"><AlertCode>N53</AlertCode><RequestId>o:d:1</RequestId></DCCAlert>";
        try (LogCapture log = new LogCapture()) {
            resource((id, m) -> true).receive(alert);

            var fields = log.lines("edt.interaction").get(0).fields();
            assertEquals("DCC_ALERT", fields.get("edt.message_type"));
            assertEquals("N53", fields.get("edt.alert_code"));
        }
    }

    static void assertInteraction(LogCapture.Line line, String action) {
        var fields = line.fields();
        assertEquals(action, fields.get("event.action"));
        assertEquals("dcc.dspGatewayReqHttp", fields.get("edt.interface"));
        assertEquals("inbound", fields.get("network.direction"));
        assertEquals("success", fields.get("event.outcome"));
        assertEquals("o:d:1", fields.get("edt.request_id"));
        assertEquals("RESPONSE", fields.get("edt.message_type"));
        assertFalse(fields.containsKey("edt.alert_code"), "a Response has no alert code, and no \"null\" text");
        assertTrue(Long.parseLong(fields.get("event.duration")) >= 0);
        assertFalse(line.message().contains("<"));
        assertFalse(fields.values().stream().anyMatch(v -> v.contains("<")), "no part of the XML is in the line");
    }

    static DspMessageResource resource(WorkflowSignaller signaller) {
        return new DspMessageResource(new RouteDspMessage(body -> { }, new XmlDspMessageReader(), signaller));
    }
}
