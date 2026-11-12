package prepare.edt.orchestrator.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Fixed inputs and expected outputs: the Response Listener's copy of the workflow ID rule
 * must give the same answer (a contract test will compare them later).
 */
class RequestIdTest {

    @Test
    void value_is_originator_target_counter() {
        RequestId id = RequestId.placeholder("90-B3-D5-1F-30-01-00-01", "00-DB-12-34-56-78-90-A0", 1000L);

        assertEquals("90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A0:1000", id.value());
    }

    @Test
    void workflow_id_is_derived_from_the_request_id_only() {
        assertEquals("service-request-90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A0:1000",
                RequestId.workflowId("90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A0:1000"));
    }

    @Test
    void only_n12_and_n13_fail_a_service_request() {
        assertTrue(DccAlerts.failsServiceRequest("N12"));
        assertTrue(DccAlerts.failsServiceRequest("N13"));
        assertFalse(DccAlerts.failsServiceRequest("N53"));
    }
}
