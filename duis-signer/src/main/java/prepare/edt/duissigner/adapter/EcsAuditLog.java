package prepare.edt.duissigner.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import prepare.edt.duissigner.infrastructure.LogFields;
import prepare.edt.duissigner.usecase.AuditLog;

/**
 * Writes one audit event per signing decision, as a structured log line (ECS JSON in production).
 * Its own logger name, "edt.audit", so the log agent can route audit events separately.
 *
 * Fields: event.kind=event, event.category=configuration, event.action=sign-service-request,
 * event.outcome=success|failure, plus the Request ID, SRV, User Role and reason, the model interface of the signing call
 * (edt.interface) and how long the signing took (event.duration, nanoseconds).
 */
public final class EcsAuditLog implements AuditLog {

    private static final Logger AUDIT = LoggerFactory.getLogger("edt.audit");

    @Override
    public void record(AuditEvent event) {
        boolean signed = event.outcome() == Outcome.SIGNED;
        try (var fields = LogFields.of(
                "event.kind", "event",
                "event.category", "configuration",
                "event.action", "sign-service-request",
                "event.outcome", signed ? "success" : "failure",
                "edt.interface", "edt.duisSignerReqKms",
                "event.duration", event.durationNanos(),
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
