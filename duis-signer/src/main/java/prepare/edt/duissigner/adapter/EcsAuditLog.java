package prepare.edt.duissigner.adapter;

import org.jboss.logging.Logger;
import prepare.edt.duissigner.infrastructure.LogFields;
import prepare.edt.duissigner.usecase.AuditLog;

/**
 * Writes one audit event per signing decision, as a structured log line (ECS JSON in production).
 * Its own logger name, "edt.audit", so the log agent can route audit events separately.
 *
 * Fields: event.kind=event, event.category=configuration, event.action=sign-service-request,
 * event.outcome=success|failure, plus the Request ID, SRV, User Role and reason.
 */
public final class EcsAuditLog implements AuditLog {

    private static final Logger AUDIT = Logger.getLogger("edt.audit");

    @Override
    public void record(AuditEvent event) {
        boolean signed = event.outcome() == Outcome.SIGNED;
        try (var fields = LogFields.of(
                "event.kind", "event",
                "event.category", "configuration",
                "event.action", "sign-service-request",
                "event.outcome", signed ? "success" : "failure",
                "edt.request_id", String.valueOf(event.requestId()),
                "edt.srv", String.valueOf(event.srv()))) {
            if (signed) {
                fields.add("edt.user_role", event.role());
                AUDIT.info("Service Request signed");
            } else {
                fields.add("edt.reason", event.reason());
                AUDIT.warn("Service Request refused");
            }
        }
    }
}
