package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.temporal.failure.ApplicationFailure;
import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import prepare.edt.orchestrator.entity.RequestId;

/**
 * The expected structure is that of the RTDS examples ECS17b (SRV 4.1.1, device ...90-A0) and CCS06 (SRV 8.9, device ...90-A2).
 */
class RequestBuilderActivityImplTest {

    static final String NS = "http://www.dccinterface.co.uk/ServiceUserGateway";
    static final String ORIGINATOR = "90-B3-D5-1F-30-01-00-01";

    final RequestBuilderActivityImpl builder = new RequestBuilderActivityImpl();

    @Test
    void a_meter_gets_srv_4_1_1_with_an_empty_read_body() throws Exception {
        Document request = parse(builder.build(input("00-DB-12-34-56-78-90-A0", "ESME", "4.1.1")));

        assertEquals("Request", request.getDocumentElement().getLocalName());
        assertEquals(NS, request.getDocumentElement().getNamespaceURI());
        assertEquals("5.5", request.getDocumentElement().getAttribute("schemaVersion"));
        assertEquals("1", text(request, "CommandVariant"));
        assertEquals("4.1", text(request, "ServiceReference"));
        assertEquals("4.1.1", text(request, "ServiceReferenceVariant"));
        assertEquals(1, request.getElementsByTagNameNS(NS, "ReadInstantaneousImportRegisters").getLength());
        assertFalse(request.getElementsByTagNameNS(NS, "ReadInstantaneousImportRegisters").item(0).hasChildNodes(),
                "the body element is empty, as in the example");
    }

    @Test
    void a_communications_hub_gets_srv_8_9_with_an_empty_read_device_log_body() throws Exception {
        Document request = parse(builder.build(input("00-DB-12-34-56-78-90-A2", "CHF", "8.9")));

        assertEquals("1", text(request, "CommandVariant"));
        assertEquals("8.9", text(request, "ServiceReference"));
        assertEquals("8.9", text(request, "ServiceReferenceVariant"));
        assertEquals(1, request.getElementsByTagNameNS(NS, "ReadDeviceLog").getLength());
        assertFalse(request.getElementsByTagNameNS(NS, "ReadDeviceLog").item(0).hasChildNodes());
    }

    @Test
    void the_request_id_is_originator_device_counter() throws Exception {
        ServiceRequestInput input = input("00-DB-12-34-56-78-90-A0", "ESME", "4.1.1");

        Document request = parse(builder.build(input));

        assertEquals(ORIGINATOR + ":00-DB-12-34-56-78-90-A0:1000", text(request, "RequestID"));
        assertEquals(input.requestId(), text(request, "RequestID"));
    }

    @Test
    void the_request_names_no_device_type_and_no_originator_element() {
        String xml = builder.build(input("00-DB-12-34-56-78-90-A0", "ESME", "4.1.1"));

        assertFalse(xml.contains("ESME"), "the device type is not in the request");
        assertFalse(xml.contains("Originator"), "the originator is the first part of the Request ID");
        assertFalse(xml.contains("urn:edt:placeholder:duis"), "no placeholder namespace is left");
        assertTrue(xml.startsWith("<sr:Request "));
    }

    @Test
    void an_srv_that_is_not_defined_fails_without_retry() {
        ApplicationFailure failure = assertThrows(ApplicationFailure.class,
                () -> builder.build(input("00-DB-12-34-56-78-90-A0", "ESME", "8.2")));

        assertTrue(failure.isNonRetryable(), "building it again gives the same result");
        assertEquals(RequestBuilderActivityImpl.UNKNOWN_SRV, failure.getType());
        assertTrue(failure.getOriginalMessage().contains("8.2"));
    }

    static ServiceRequestInput input(String device, String deviceType, String srv) {
        return new ServiceRequestInput(RequestId.placeholder(ORIGINATOR, device, 1000L).value(), "batch-1",
                ORIGINATOR, device, deviceType, srv);
    }

    static Document parse(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    static String text(Document document, String element) {
        return document.getElementsByTagNameNS(NS, element).item(0).getTextContent();
    }
}
