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

    // ---- the Response the simulator writes (RTDS ECS17b, GCS13a, CCS06 shapes) ----

    static final String HEADER_ORIGINATOR = "<ra:BusinessOriginatorID>00-DB-12-34-56-78-90-A0</ra:BusinessOriginatorID>";

    @Test
    void the_response_for_an_electricity_meter_has_the_ecs17b_shape() throws Exception {
        String xml = DspMessages.response(DspMessages.read(REQUEST_4_1_1), java.util.Set.of());

        assertTrue(xml.contains(HEADER_ORIGINATOR));
        assertTrue(xml.contains("<ra:BusinessTargetID>90-B3-D5-1F-30-01-00-01</ra:BusinessTargetID>"));
        assertTrue(xml.contains("<ra:OriginatorCounter>1000</ra:OriginatorCounter>"));
        assertTrue(xml.contains("<ra:ServiceReference>4.1</ra:ServiceReference>"));
        assertTrue(xml.contains("MessageSuccess=\"true\""));
        assertTrue(xml.contains("<ra:ActiveEnergyUnit>Wh</ra:ActiveEnergyUnit>"));
        assertTrue(xml.contains("<ra:ReactiveEnergyUnit>varh</ra:ReactiveEnergyUnit>"));
        assertFalse(xml.contains("<ra:Gas>"));
    }

    @Test
    void a_device_listed_as_gas_gets_a_gas_reading() throws Exception {
        String xml = DspMessages.response(DspMessages.read(REQUEST_4_1_1), java.util.Set.of("00-DB-12-34-56-78-90-A0"));

        assertTrue(xml.contains("<ra:Value>12.345</ra:Value><ra:Unit>m3</ra:Unit>"));
        assertFalse(xml.contains("<ra:Electricity>"));
    }

    @Test
    void the_device_log_response_goes_through_the_broker_with_the_supplementary_fields() throws Exception {
        String xml = DspMessages.response(DspMessages.read(REQUEST_8_9), java.util.Set.of());

        assertTrue(xml.contains("<ra:BusinessOriginatorID>00-DB-12-34-56-78-90-A2</ra:BusinessOriginatorID>"));
        assertTrue(xml.contains("<ra:BusinessTargetID>" + DspMessages.ACCESS_CONTROL_BROKER + "</ra:BusinessTargetID>"));
        assertTrue(xml.contains("<ra:SupplementaryRemotePartyID>90-B3-D5-1F-30-01-00-01</ra:SupplementaryRemotePartyID>"));
        assertTrue(xml.contains("<ra:SupplementaryRemotePartyCounter>1000</ra:SupplementaryRemotePartyCounter>"));
        assertEquals(2, xml.split("<ra:CHFDeviceLogEntry>", -1).length - 1);
    }

    @Test
    void tampering_a_response_flips_message_success() throws Exception {
        String xml = DspMessages.response(DspMessages.read(REQUEST_4_1_1), java.util.Set.of());

        assertTrue(DspMessages.tamper(xml).contains("MessageSuccess=\"false\""));
    }
}
