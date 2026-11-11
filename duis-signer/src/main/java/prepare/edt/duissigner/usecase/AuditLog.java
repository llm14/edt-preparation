package prepare.edt.duissigner.usecase;

import prepare.edt.duissigner.entity.UserRole;

/** Port: records one audit event per signing decision. */
public interface AuditLog {

    void record(AuditEvent event);

    /**
     * One signing decision.
     *
     * @param requestId the DUIS Request ID, or null if the XML could not be read
     * @param srv       the SRV, or null if the XML could not be read
     * @param outcome   SIGNED or REFUSED
     * @param role      the role it was signed as, or null if refused
     * @param reason    why it was refused, or null if signed
     */
    record AuditEvent(String requestId, String srv, Outcome outcome, UserRole role, String reason) {
    }

    enum Outcome { SIGNED, REFUSED }
}
