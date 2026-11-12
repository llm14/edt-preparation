package prepare.edt.duissigner.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import prepare.edt.duissigner.entity.ServiceRequest;
import prepare.edt.duissigner.usecase.ServiceRequestReader.InvalidServiceRequestException;

class XmlServiceRequestReaderTest {

    final XmlServiceRequestReader reader = new XmlServiceRequestReader();

    @Test
    void reads_the_values_of_a_valid_request() {
        ServiceRequest request = reader.read(JcaXmlSignerTest.UNSIGNED);

        assertEquals("REQ-1", request.requestId());
        assertEquals("4.1.1", request.srv());
        assertEquals("90-B3-D5-1F-30-01-00-01", request.originator());
        assertEquals("ESME", request.deviceType());
        assertEquals(JcaXmlSignerTest.UNSIGNED, request.xml());
    }

    @Test
    void refuses_a_request_without_srv() {
        String missingSrv = JcaXmlSignerTest.UNSIGNED.replace("<SRV>4.1.1</SRV>", "");

        assertThrows(InvalidServiceRequestException.class, () -> reader.read(missingSrv));
    }

    @Test
    void refuses_xml_that_is_not_well_formed() {
        assertThrows(InvalidServiceRequestException.class, () -> reader.read("<ServiceRequest>"));
    }

    @Test
    void refuses_a_dtd_so_no_external_entity_can_be_read() {
        String withDtd = """
                <!DOCTYPE ServiceRequest [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                """ + JcaXmlSignerTest.UNSIGNED.replace("REQ-1", "&secret;");

        assertThrows(InvalidServiceRequestException.class, () -> reader.read(withDtd));
    }
}
