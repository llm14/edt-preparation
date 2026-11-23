package prepare.edt.duissigner.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import prepare.edt.duissigner.entity.ServiceRequest;
import prepare.edt.duissigner.usecase.ServiceRequestReader.InvalidServiceRequestException;

/**
 * The messages are written from the examples of the Reference Test Data Set (RTDS 4.10.0), unsigned:
 * ECS17b (SRV 4.1.1, device ...90-A0) and CCS06 (SRV 8.9, device ...90-A2).
 */
class XmlServiceRequestReaderTest {

    /** RTDS 4.1.1_ECS17b, with the originator of the placeholder Other User role. Same as JcaXmlSignerTest.UNSIGNED. */
    static final String SRV_4_1_1 = JcaXmlSignerTest.UNSIGNED;

    /** RTDS 8.9_CCS06: Read Device Log of a Communications Hub, empty body. */
    static final String SRV_8_9 = """
            <sr:Request xmlns:sr="http://www.dccinterface.co.uk/ServiceUserGateway" schemaVersion="5.5">\
            <sr:Header><sr:RequestID>90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A2:1000</sr:RequestID>\
            <sr:CommandVariant>1</sr:CommandVariant><sr:ServiceReference>8.9</sr:ServiceReference>\
            <sr:ServiceReferenceVariant>8.9</sr:ServiceReferenceVariant></sr:Header>\
            <sr:Body><sr:ReadDeviceLog/></sr:Body>\
            </sr:Request>""";

    final XmlServiceRequestReader reader = new XmlServiceRequestReader();

    @Test
    void reads_the_values_of_a_4_1_1_request() {
        ServiceRequest request = reader.read(SRV_4_1_1, "ESME");

        assertEquals("90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A0:1000", request.requestId());
        assertEquals("4.1.1", request.srv());
        assertEquals("90-B3-D5-1F-30-01-00-01", request.originator(), "the first part of the Request ID");
        assertEquals("ESME", request.deviceType(), "given by the caller: the request does not carry it");
        assertEquals(SRV_4_1_1, request.xml());
    }

    @Test
    void reads_the_values_of_an_8_9_request() {
        ServiceRequest request = reader.read(SRV_8_9, "CHF");

        assertEquals("8.9", request.srv());
        assertEquals("90-B3-D5-1F-30-01-00-01", request.originator());
        assertEquals("CHF", request.deviceType());
    }

    @Test
    void refuses_another_namespace() {
        String placeholder = SRV_4_1_1.replace("http://www.dccinterface.co.uk/ServiceUserGateway", "urn:edt:placeholder:duis");

        assertThrows(InvalidServiceRequestException.class, () -> reader.read(placeholder, "ESME"));
    }

    @Test
    void refuses_a_body_with_two_request_elements() {
        String twoElements = SRV_4_1_1.replace("<sr:ReadInstantaneousImportRegisters/>",
                "<sr:ReadInstantaneousImportRegisters/><sr:ReadDeviceLog/>");

        assertThrows(InvalidServiceRequestException.class, () -> reader.read(twoElements, "ESME"));
    }

    @Test
    void refuses_a_body_that_is_not_a_known_request_element() {
        String unknown = SRV_4_1_1.replace("ReadInstantaneousImportRegisters", "DisableSupply");

        assertThrows(InvalidServiceRequestException.class, () -> reader.read(unknown, "ESME"));
    }

    @Test
    void refuses_a_request_without_its_service_reference_variant() {
        String missing = SRV_4_1_1.replace("<sr:ServiceReferenceVariant>4.1.1</sr:ServiceReferenceVariant>", "");

        assertThrows(InvalidServiceRequestException.class, () -> reader.read(missing, "ESME"));
    }

    @Test
    void refuses_a_command_variant_other_than_1() {
        String critical = SRV_4_1_1.replace("<sr:CommandVariant>1<", "<sr:CommandVariant>4<");

        assertThrows(InvalidServiceRequestException.class, () -> reader.read(critical, "ESME"));
    }

    @Test
    void refuses_a_request_id_that_is_not_originator_device_counter() {
        String bad = SRV_4_1_1.replace("90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A0:1000", "REQ-1");

        assertThrows(InvalidServiceRequestException.class, () -> reader.read(bad, "ESME"));
    }

    @Test
    void refuses_a_request_that_is_already_signed() {
        String signed = SRV_4_1_1.replace("</sr:Request>",
                "<ds:Signature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"/></sr:Request>");

        assertThrows(InvalidServiceRequestException.class, () -> reader.read(signed, "ESME"));
    }

    @Test
    void refuses_xml_that_is_not_well_formed() {
        assertThrows(InvalidServiceRequestException.class, () -> reader.read("<sr:Request>", "ESME"));
    }

    @Test
    void refuses_a_dtd_so_no_external_entity_can_be_read() {
        String withDtd = """
                <!DOCTYPE sr:Request [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                """ + SRV_4_1_1.replace("1000<", "&secret;<");

        assertThrows(InvalidServiceRequestException.class, () -> reader.read(withDtd, "ESME"));
    }
}
