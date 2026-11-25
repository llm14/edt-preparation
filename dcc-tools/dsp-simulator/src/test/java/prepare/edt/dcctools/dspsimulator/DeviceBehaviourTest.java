package prepare.edt.dcctools.dspsimulator;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void the_tampered_variants_are_chosen_by_a6_and_a7() {
        assertEquals(DeviceBehaviour.TAMPERED_RESPONSE, DeviceBehaviour.forTarget("00-DB-12-34-56-78-90-A6"));
        assertEquals(DeviceBehaviour.TAMPERED_SYNC_RESPONSE, DeviceBehaviour.forTarget("00-DB-12-34-56-78-90-A7"));
    }
}
