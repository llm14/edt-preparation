package prepare.edt.duissigner.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import prepare.edt.duissigner.entity.UserRole;
import prepare.edt.duissigner.usecase.AuditLog.AuditEvent;
import prepare.edt.duissigner.usecase.AuditLog.Outcome;

/** The audit line stays on edt.audit and now says which model interface it crossed and how long the signing took. */
class EcsAuditLogTest {

    final EcsAuditLog audit = new EcsAuditLog();

    @Test
    void a_signed_event_has_the_interface_and_the_duration_and_stays_on_the_audit_logger() {
        try (LogCapture log = new LogCapture()) {
            audit.record(new AuditEvent("REQ-1", "4.1.1", Outcome.SIGNED, UserRole.OTHER_USER, null, 1_500_000));

            assertEquals(List.of(), log.lines("edt.interaction"), "the audit line is not an interaction line");
            var lines = log.lines("edt.audit");
            assertEquals(1, lines.size());
            var fields = lines.get(0).fields();
            assertEquals("sign-service-request", fields.get("event.action"));
            assertEquals("success", fields.get("event.outcome"));
            assertEquals("edt.duisSignerReqKms", fields.get("edt.interface"));
            assertEquals("1500000", fields.get("event.duration"));
            assertEquals("REQ-1", fields.get("edt.request_id"));
        }
    }

    @Test
    void a_refused_event_has_them_too() {
        try (LogCapture log = new LogCapture()) {
            audit.record(new AuditEvent("REQ-1", "9.9.9", Outcome.REFUSED, null, "not allowed", 42));

            var fields = log.lines("edt.audit").get(0).fields();
            assertEquals("failure", fields.get("event.outcome"));
            assertEquals("edt.duisSignerReqKms", fields.get("edt.interface"));
            assertTrue(Long.parseLong(fields.get("event.duration")) >= 0);
            assertEquals("not allowed", fields.get("edt.reason"));
        }
    }
}
