package prepare.edt.dcctools.dspsimulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The simulator reads real DUIS 5.5 requests. The messages are written from the examples of the Reference Test Data Set
 * (RTDS 4.10.0): 4.1.1_ECS17b (device ...90-A0) and 8.9_CCS06 (device ...90-A2), with a placeholder signature element.
 */
class DspMessagesTest {

    static final String SIGNATURE = "<ds:Signature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"/>";

    static String request(String requestId, String srvRef, String srv, String body, String tail) {
        return "<sr:Request xmlns:sr=\"http://www.dccinterface.co.uk/ServiceUserGateway\" schemaVersion=\"5.5\">"
                + "<sr:Header><sr:RequestID>" + requestId + "</sr:RequestID><sr:CommandVariant>1</sr:CommandVariant>"
                + "<sr:ServiceReference>" + srvRef + "</sr:ServiceReference>"
                + "<sr:ServiceReferenceVariant>" + srv + "</sr:ServiceReferenceVariant></sr:Header>"
                + "<sr:Body><sr:" + body + "/></sr:Body>" + tail + "</sr:Request>";
    }

    static final String REQUEST_4_1_1 = request("90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A0:1000",
            "4.1", "4.1.1", "ReadInstantaneousImportRegisters", SIGNATURE);

    static final String REQUEST_8_9 = request("90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A2:1000",
            "8.9", "8.9", "ReadDeviceLog", SIGNATURE);

    @Test
    void a_signed_4_1_1_request_is_read() throws Exception {
        var request = DspMessages.read(REQUEST_4_1_1);

        assertEquals("90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A0:1000", request.requestId());
        assertEquals("00-DB-12-34-56-78-90-A0", request.targetDevice(), "the middle part of the Request ID");
        assertEquals("4.1.1", request.srv());
        assertTrue(request.signed());
    }

    @Test
    void a_signed_8_9_request_is_read() throws Exception {
        var request = DspMessages.read(REQUEST_8_9);

        assertEquals("00-DB-12-34-56-78-90-A2", request.targetDevice());
        assertEquals("8.9", request.srv());
        assertTrue(request.signed());
    }

    @Test
    void an_unsigned_request_is_noticed() throws Exception {
        var request = DspMessages.read(request("90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A0:1000",
                "4.1", "4.1.1", "ReadInstantaneousImportRegisters", ""));

        assertFalse(request.signed());
    }

    @Test
    void the_device_behaviour_is_chosen_by_the_device_in_the_request_id() throws Exception {
        var request = DspMessages.read(request("90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A1:1000",
                "4.1", "4.1.1", "ReadInstantaneousImportRegisters", SIGNATURE));

        assertEquals(DeviceBehaviour.ALERT_N12, DeviceBehaviour.forTarget(request.targetDevice()));
    }

    @Test
    void a_request_id_that_is_not_originator_device_counter_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> DspMessages.read(
                request("REQ-1", "4.1", "4.1.1", "ReadInstantaneousImportRegisters", SIGNATURE)));
    }

    @Test
    void a_placeholder_request_is_no_longer_read() {
        assertThrows(IllegalArgumentException.class, () -> DspMessages.read("""
                <ServiceRequest xmlns="urn:edt:placeholder:duis"><Header><RequestId>R-1</RequestId></Header>\
                <Body><Target>00-DB-12-34-56-78-90-A1</Target></Body></ServiceRequest>"""));
    }
}
