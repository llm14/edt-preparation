package prepare.edt.responselistener.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import prepare.edt.responselistener.entity.DspMessage;
import prepare.edt.responselistener.usecase.DspMessageReader.UnreadableMessageException;

/** The messages below are what the DSP simulator (dcc-tools) sends. */
class XmlDspMessageReaderTest {

    final XmlDspMessageReader reader = new XmlDspMessageReader();

    @Test
    void reads_a_response() {
        String xml = "<Response xmlns=\"urn:edt:placeholder:duis\"><RequestId>o:d:1</RequestId>"
                + "<ResponseCode>I0</ResponseCode><Body><InstantaneousImportRegister>1</InstantaneousImportRegister></Body>"
                + "</Response>";

        DspMessage message = reader.read(xml);

        assertEquals(DspMessage.Kind.RESPONSE, message.kind());
        assertEquals("o:d:1", message.requestId());
        assertNull(message.alertCode());
        assertEquals(xml, message.xml());
    }

    @Test
    void reads_a_dcc_alert() {
        DspMessage message = reader.read("<DCCAlert xmlns=\"urn:edt:placeholder:duis\">"
                + "<AlertCode>N12</AlertCode><RequestId>o:d:1</RequestId></DCCAlert>");

        assertEquals(DspMessage.Kind.DCC_ALERT, message.kind());
        assertEquals("N12", message.alertCode());
    }

    @Test
    void refuses_a_message_without_request_id() {
        assertThrows(UnreadableMessageException.class, () ->
                reader.read("<Response xmlns=\"urn:edt:placeholder:duis\"><ResponseCode>I0</ResponseCode></Response>"));
    }

    @Test
    void refuses_other_documents_and_namespaces() {
        assertThrows(UnreadableMessageException.class, () ->
                reader.read("<ServiceRequest xmlns=\"urn:edt:placeholder:duis\"><RequestId>x</RequestId></ServiceRequest>"));
        assertThrows(UnreadableMessageException.class, () ->
                reader.read("<Response><RequestId>x</RequestId></Response>"));
    }

    @Test
    void refuses_a_dtd() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<Response xmlns=\"urn:edt:placeholder:duis\"><RequestId>&x;</RequestId></Response>";

        assertThrows(UnreadableMessageException.class, () -> reader.read(xxe));
    }
}
