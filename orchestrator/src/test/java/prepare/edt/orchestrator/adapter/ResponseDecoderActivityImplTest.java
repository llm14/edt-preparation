package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

/** A Response that cannot be decoded is an expected rejection: the reason is in error.message and there is no stack. */
class ResponseDecoderActivityImplTest {

    @Test
    void an_undecodable_response_keeps_error_message_as_a_field_and_has_no_exception() {
        var activity = new ResponseDecoderActivityImpl(new PlaceholderResponseDecoder());
        try (LogCapture log = new LogCapture()) {
            assertThrows(ApplicationFailure.class,
                    () -> activity.decode("4.1.1", "<Response xmlns=\"urn:edt:placeholder:duis\"/>"));

            var line = log.lines(ResponseDecoderActivityImpl.class).get(0);
            assertEquals("response-not-decodable", line.fields().get("event.action"));
            assertTrue(line.fields().containsKey("error.message"));
            assertEquals(null, line.thrown());
        }
    }
}
