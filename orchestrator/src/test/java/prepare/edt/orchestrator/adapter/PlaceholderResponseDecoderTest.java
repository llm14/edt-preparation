package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.failure.ApplicationFailure;
import java.util.Map;
import org.junit.jupiter.api.Test;
import prepare.edt.orchestrator.entity.Facts;
import prepare.edt.orchestrator.usecase.ResponseDecoder.UndecodableResponseException;

class PlaceholderResponseDecoderTest {

    final PlaceholderResponseDecoder decoder = new PlaceholderResponseDecoder();

    @Test
    void takes_each_simple_element_of_the_body_as_a_fact() {
        String response = "<Response xmlns=\"urn:edt:placeholder:duis\"><RequestId>R</RequestId>"
                + "<ResponseCode>I0</ResponseCode><Body><PaymentMode>Credit</PaymentMode>"
                + "<InstantaneousImportRegister> 12345.6 </InstantaneousImportRegister></Body>"
                + "<Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\"><SignatureValue>x</SignatureValue></Signature>"
                + "</Response>";

        Facts facts = decoder.decode("4.1.1", response);

        assertEquals(Map.of("InstantaneousImportRegister", "12345.6", "PaymentMode", "Credit"), facts.values());
    }

    @Test
    void a_response_without_body_cannot_be_decoded() {
        assertThrows(UndecodableResponseException.class,
                () -> decoder.decode("4.1.1", "<Response xmlns=\"urn:edt:placeholder:duis\"/>"));
    }

    @Test
    void other_documents_cannot_be_decoded() {
        assertThrows(UndecodableResponseException.class, () -> decoder.decode("4.1.1", "<Response><Body/></Response>"));
        assertThrows(UndecodableResponseException.class, () -> decoder.decode("4.1.1", "not xml"));
    }

    @Test
    void the_activity_turns_it_into_a_non_retryable_failure() {
        var activity = new ResponseDecoderActivityImpl(decoder);

        var failure = assertThrows(ApplicationFailure.class, () -> activity.decode("4.1.1", "not xml"));

        assertEquals(ResponseDecoderActivityImpl.NOT_DECODABLE, failure.getType());
        assertTrue(failure.isNonRetryable());
    }
}
