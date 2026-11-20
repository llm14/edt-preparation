package prepare.edt.orchestrator.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import prepare.edt.orchestrator.adapter.LogCapture;

/** LogFields puts its keys in the MDC of the lines written inside the block, and takes them out when the block closes. */
class LogFieldsTest {

    private static final Logger LOG = LoggerFactory.getLogger("test.logfields");

    @Test
    void the_keys_are_on_the_lines_inside_the_block_and_gone_after_it() {
        try (LogCapture log = new LogCapture()) {
            try (var fields = LogFields.of("event.action", "test-action", "edt.count", 3)) {
                LOG.info("inside");
            }
            LOG.info("outside");

            var inside = log.lines("test.logfields").stream().filter(l -> "inside".equals(l.message())).findFirst().orElseThrow();
            var outside = log.lines("test.logfields").stream().filter(l -> "outside".equals(l.message())).findFirst().orElseThrow();
            assertEquals("test-action", inside.fields().get("event.action"));
            assertEquals("3", inside.fields().get("edt.count"), "a value of another type is written as its text");
            assertFalse(outside.fields().containsKey("event.action"));
            assertFalse(outside.fields().containsKey("edt.count"));
        }
    }

    @Test
    void add_puts_one_more_key_that_is_also_removed_on_close() {
        try (LogCapture log = new LogCapture()) {
            try (var fields = LogFields.of("event.action", "test-action")) {
                fields.add("edt.extra", "x");
                LOG.info("inside");
            }
            LOG.info("outside");

            var inside = log.lines("test.logfields").stream().filter(l -> "inside".equals(l.message())).findFirst().orElseThrow();
            var outside = log.lines("test.logfields").stream().filter(l -> "outside".equals(l.message())).findFirst().orElseThrow();
            assertEquals("x", inside.fields().get("edt.extra"));
            assertFalse(outside.fields().containsKey("edt.extra"));
        }
    }
}
