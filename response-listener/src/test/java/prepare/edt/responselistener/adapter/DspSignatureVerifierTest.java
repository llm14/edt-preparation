package prepare.edt.responselistener.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DspSignatureVerifierTest {

    static final String RESPONSE = "<ra:GBCSResponse xmlns:ra=\"http://www.dccinterface.co.uk/ResponseAndAlert\" schemaVersion=\"5.5\"><ra:Header>"
            + "<ra:BusinessOriginatorID>00-DB-12-34-56-78-90-A0</ra:BusinessOriginatorID>"
            + "<ra:BusinessTargetID>90-B3-D5-1F-30-01-00-00</ra:BusinessTargetID>"
            + "<ra:OriginatorCounter>1000</ra:OriginatorCounter></ra:Header><ra:Body><ra:ResponseMessage><ra:SMETSData>"
            + "<ra:ReadInstantaneousImportRegistersRsp MessageSuccess=\"true\"/></ra:SMETSData></ra:ResponseMessage></ra:Body>"
            + "</ra:GBCSResponse>";

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
        assertTrue(verifier.check(dsp.sign(RESPONSE)).valid());
    }

    @Test
    void a_changed_message_is_not() throws Exception {
        String tampered = dsp.sign(RESPONSE).replace("MessageSuccess=\"true\"", "MessageSuccess=\"false\"");

        var verdict = verifier.check(tampered);
        assertFalse(verdict.valid());
        assertEquals("signature does not match", verdict.reason());
    }

    @Test
    void a_message_signed_by_another_key_is_not_even_with_its_certificate_inside() throws Exception {
        assertFalse(verifier.check(impostor.sign(RESPONSE)).valid(), "only the trusted certificate counts");
    }

    @Test
    void an_unsigned_message_is_not() {
        var verdict = verifier.check(RESPONSE);
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
