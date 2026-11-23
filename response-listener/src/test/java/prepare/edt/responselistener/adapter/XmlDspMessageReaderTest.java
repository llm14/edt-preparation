package prepare.edt.responselistener.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import prepare.edt.responselistener.entity.DspMessage;
import prepare.edt.responselistener.usecase.DspMessageReader.UnreadableMessageException;

/** Real Responses are written from the RTDS 4.10.0 headers (ECS17b, CCS06); DCC Alerts are still placeholder. */
class XmlDspMessageReaderTest {

    final XmlDspMessageReader reader = new XmlDspMessageReader();

    static String ra(String header, String body) {
        return "<ra:GBCSResponse xmlns:ra=\"http://www.dccinterface.co.uk/ResponseAndAlert\" schemaVersion=\"5.5\">"
                + "<ra:Header>" + header + "</ra:Header><ra:Body>" + body + "</ra:Body></ra:GBCSResponse>";
    }

    static final String RESPONSE_MESSAGE = "<ra:ResponseMessage><ra:SMETSData/></ra:ResponseMessage>";

    /** Header of RTDS ECS17b_4.1.1_SINGLE_SUCCESS_RESPONSE_MMC. */
    static final String ECS17B_HEADER = "<ra:BusinessOriginatorID>00-DB-12-34-56-78-90-A0</ra:BusinessOriginatorID>"
            + "<ra:BusinessTargetID>90-B3-D5-1F-30-01-00-00</ra:BusinessTargetID><ra:OriginatorCounter>1000</ra:OriginatorCounter>";

    /** Header of RTDS CCS06_8.9_SUCCESS_RESPONSE_MMC (through the Communications Hub, supplementary fields). */
    static final String CCS06_HEADER = "<ra:BusinessOriginatorID>00-DB-12-34-56-78-90-A2</ra:BusinessOriginatorID>"
            + "<ra:BusinessTargetID>90-B3-D5-1F-30-00-00-02</ra:BusinessTargetID><ra:OriginatorCounter>1300</ra:OriginatorCounter>"
            + "<ra:SupplementaryRemotePartyID>90-B3-D5-1F-30-01-00-00</ra:SupplementaryRemotePartyID>"
            + "<ra:SupplementaryRemotePartyCounter>1000</ra:SupplementaryRemotePartyCounter>";

    @Test
    void reads_a_response_and_rebuilds_the_request_id_from_the_header() {
        String xml = ra(ECS17B_HEADER, RESPONSE_MESSAGE);

        DspMessage message = reader.read(xml);

        assertEquals(DspMessage.Kind.RESPONSE, message.kind());
        assertEquals("90-B3-D5-1F-30-01-00-00:00-DB-12-34-56-78-90-A0:1000", message.requestId());
        assertNull(message.alertCode());
        assertEquals(xml, message.xml());
    }

    @Test
    void a_response_through_the_hub_uses_the_supplementary_fields() {
        assertEquals("90-B3-D5-1F-30-01-00-00:00-DB-12-34-56-78-90-A2:1000",
                reader.read(ra(CCS06_HEADER, RESPONSE_MESSAGE)).requestId());
    }

    @Test
    void refuses_a_response_with_an_incomplete_header() {
        assertThrows(UnreadableMessageException.class, () -> reader.read(ra(
                "<ra:BusinessOriginatorID>x</ra:BusinessOriginatorID><ra:OriginatorCounter>1</ra:OriginatorCounter>",
                RESPONSE_MESSAGE)));
    }

    @Test
    void refuses_a_device_alert() {
        // Header of RTDS ECS01a_1.1.1_8F66 alert; the Body holds a DeviceAlertMessage, not a ResponseMessage
        assertThrows(UnreadableMessageException.class, () -> reader.read(ra(ECS17B_HEADER,
                "<ra:DeviceAlertMessage><ra:DeviceAlertContent><ra:GBCSHexAlertCode>8F66</ra:GBCSHexAlertCode>"
                        + "</ra:DeviceAlertContent></ra:DeviceAlertMessage>")));
    }

    @Test
    void refuses_a_response_in_the_placeholder_format() {
        assertThrows(UnreadableMessageException.class, () -> reader.read(
                "<Response xmlns=\"urn:edt:placeholder:duis\"><RequestId>o:d:1</RequestId></Response>"));
    }

    @Test
    void reads_a_dcc_alert() {
        DspMessage message = reader.read("<DCCAlert xmlns=\"urn:edt:placeholder:duis\">"
                + "<AlertCode>N12</AlertCode><RequestId>o:d:1</RequestId></DCCAlert>");

        assertEquals(DspMessage.Kind.DCC_ALERT, message.kind());
        assertEquals("N12", message.alertCode());
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
                + "<ra:GBCSResponse xmlns:ra=\"http://www.dccinterface.co.uk/ResponseAndAlert\"><ra:Header><ra:BusinessOriginatorID>&x;</ra:BusinessOriginatorID></ra:Header></ra:GBCSResponse>";

        assertThrows(UnreadableMessageException.class, () -> reader.read(xxe));
    }
}
