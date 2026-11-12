package prepare.edt.responselistener.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DspSignatureVerifierTest {

    static final String SYNC = "<SyncResponse xmlns=\"urn:edt:placeholder:duis\"><RequestId>R-1</RequestId>"
            + "<ResponseCode>I0</ResponseCode><Detail>accepted</Detail></SyncResponse>";

    static TestDsp dsp;
    static TestDsp impostor;
    static DspSignatureVerifier verifier;

    @BeforeAll
    static void keys(@TempDir Path directory) throws Exception {
        dsp = TestDsp.create(directory, "dsp");
        impostor = TestDsp.create(directory, "impostor");
        verifier = new DspSignatureVerifier(dsp.certificate);
    }

    @Test
    void a_message_signed_by_the_dsp_is_valid() throws Exception {
        assertTrue(verifier.check(dsp.sign(SYNC)).valid());
    }

    @Test
    void a_changed_message_is_not() throws Exception {
        String tampered = dsp.sign(SYNC).replace("<ResponseCode>I0<", "<ResponseCode>I99<");

        var verdict = verifier.check(tampered);
        assertFalse(verdict.valid());
        assertEquals("signature does not match", verdict.reason());
    }

    @Test
    void a_message_signed_by_another_key_is_not_even_with_its_certificate_inside() throws Exception {
        assertFalse(verifier.check(impostor.sign(SYNC)).valid(), "only the trusted certificate counts");
    }

    @Test
    void an_unsigned_message_is_not() {
        var verdict = verifier.check(SYNC);
        assertFalse(verdict.valid());
        assertEquals("not signed", verdict.reason());
    }

    @Test
    void a_signature_over_part_of_the_document_is_not() throws Exception {
        String partial = "<SyncResponse xmlns=\"urn:edt:placeholder:duis\"><Part Id=\"p\">x</Part>"
                + "<ResponseCode>I0</ResponseCode></SyncResponse>";

        assertFalse(verifier.check(dsp.sign(partial, "#p")).valid());
    }

    @Test
    void junk_is_not() {
        assertFalse(verifier.check("not xml").valid());
    }
}
