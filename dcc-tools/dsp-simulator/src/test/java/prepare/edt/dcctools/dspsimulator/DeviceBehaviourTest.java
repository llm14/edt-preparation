package prepare.edt.dcctools.dspsimulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeviceBehaviourTest {

    @Test
    void the_last_group_of_the_device_id_chooses_the_behaviour() {
        assertEquals(DeviceBehaviour.RESPOND, DeviceBehaviour.forTarget("00-DB-12-34-56-78-90-A0"));
        assertEquals(DeviceBehaviour.ALERT_N12, DeviceBehaviour.forTarget("00-DB-12-34-56-78-90-A1"));
        assertEquals(DeviceBehaviour.ALERT_N13, DeviceBehaviour.forTarget("00-DB-12-34-56-78-90-a2"));
        assertEquals(DeviceBehaviour.SILENT, DeviceBehaviour.forTarget("00-DB-12-34-56-78-90-A3"));
        assertEquals(DeviceBehaviour.REJECT, DeviceBehaviour.forTarget("00-DB-12-34-56-78-90-A4"));
        assertEquals(DeviceBehaviour.ALERT_N53_THEN_RESPOND, DeviceBehaviour.forTarget("00-DB-12-34-56-78-90-A5"));
        assertEquals(DeviceBehaviour.RESPOND, DeviceBehaviour.forTarget("00-DB-12-34-56-78-90-FF"));
    }

    @Test
    void a_signed_request_is_read() throws Exception {
        var request = DspMessages.read("""
                <ServiceRequest xmlns="urn:edt:placeholder:duis"><Header><RequestId>R-1</RequestId></Header>\
                <Body><Target>00-DB-12-34-56-78-90-A1</Target></Body>\
                <Signature xmlns="http://www.w3.org/2000/09/xmldsig#"/></ServiceRequest>""");

        assertEquals("R-1", request.requestId());
        assertEquals("00-DB-12-34-56-78-90-A1", request.targetDevice());
        assertTrue(request.signed());
    }

    @Test
    void an_unsigned_request_is_noticed() throws Exception {
        var request = DspMessages.read("""
                <ServiceRequest xmlns="urn:edt:placeholder:duis"><Header><RequestId>R-1</RequestId></Header>\
                <Body><Target>T-A0</Target></Body></ServiceRequest>""");

        assertFalse(request.signed());
    }
}
