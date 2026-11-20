package prepare.edt.orchestrator.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import prepare.edt.orchestrator.adapter.LogCapture;

/** Interaction writes one line on edt.interaction with the common keys, and leaves nothing in the MDC. */
class InteractionTest {

    @Test
    void the_line_has_the_common_keys_and_a_duration() {
        try (LogCapture log = new LogCapture()) {
            try (var call = Interaction.outbound("edt.test", "test-called")) {
                call.log("called");
            }
            var line = log.lines("edt.interaction").getFirst();
            assertEquals("called", line.message());
            assertEquals("test-called", line.fields().get("event.action"));
            assertEquals("edt.test", line.fields().get("edt.interface"));
            assertEquals("outbound", line.fields().get("network.direction"));
            assertEquals("success", line.fields().get("event.outcome"));
            assertTrue(Long.parseLong(line.fields().get("event.duration")) >= 0, "the duration is not negative");
        }
    }

    @Test
    void an_inbound_call_is_marked_inbound() {
        try (LogCapture log = new LogCapture()) {
            try (var call = Interaction.inbound("edt.test", "test-received")) {
                call.log("received");
            }
            assertEquals("inbound", log.lines("edt.interaction").getFirst().fields().get("network.direction"));
        }
    }

    @Test
    void an_inbound_call_has_a_duration_only_when_timed_or_set() {
        try (LogCapture log = new LogCapture()) {
            try (var call = Interaction.inbound("edt.test", "plain")) {
                call.log("plain");
            }
            try (var call = Interaction.inbound("edt.test", "timed").timed()) {
                call.log("timed");
            }
            try (var call = Interaction.inbound("edt.test", "set").duration(42)) {
                call.log("set");
            }
            var lines = log.lines("edt.interaction");
            assertFalse(lines.get(0).fields().containsKey("event.duration"));
            assertTrue(Long.parseLong(lines.get(1).fields().get("event.duration")) >= 0);
            assertEquals("42", lines.get(2).fields().get("event.duration"));
        }
    }

    @Test
    void failure_can_be_set_and_extra_fields_added() {
        try (LogCapture log = new LogCapture()) {
            try (var call = Interaction.outbound("edt.test", "test-called")) {
                call.with("http.response.status_code", 500).failure().log("failed");
            }
            var line = log.lines("edt.interaction").getFirst();
            assertEquals("failure", line.fields().get("event.outcome"));
            assertEquals("500", line.fields().get("http.response.status_code"));
        }
    }

    @Test
    void nothing_is_left_in_the_mdc_after_the_block() {
        try (LogCapture log = new LogCapture()) {
            try (var call = Interaction.outbound("edt.test", "test-called").with("edt.extra", "x")) {
                call.log("inside");
            }
            LoggerFactory.getLogger("test.interaction").info("outside");
            var outside = log.lines("test.interaction").getFirst();
            for (String key : new String[] {"event.action", "edt.interface", "network.direction", "event.outcome",
                    "event.duration", "edt.extra"}) {
                assertFalse(outside.fields().containsKey(key), key + " was left in the MDC");
            }
        }
    }
}
