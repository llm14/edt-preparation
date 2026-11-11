package prepare.edt.duissigner.usecase;

import prepare.edt.duissigner.entity.ServiceRequest;
import prepare.edt.duissigner.entity.SigningDecision;
import prepare.edt.duissigner.entity.SigningPolicy;
import prepare.edt.duissigner.usecase.AuditLog.AuditEvent;
import prepare.edt.duissigner.usecase.AuditLog.Outcome;
import prepare.edt.duissigner.usecase.ServiceRequestReader.InvalidServiceRequestException;

/**
 * Use case: sign one Service Request, or refuse it.
 *
 *   1. read the XML and check it against the schema   (ServiceRequestReader)
 *   2. decide: which User Role, or refuse              (SigningPolicy)
 *   3. sign with that role's key                       (Signer)
 *   4. record the decision                             (AuditLog)
 *
 * Every decision is audited. A signed request is only returned after its audit event is recorded:
 * if the audit fails, the exception propagates and no signature leaves the signer.
 * Technical errors (for example the signing service is down) are not decisions: they propagate too,
 * and the caller (Temporal) may retry.
 */
public final class SignServiceRequest {

    private final ServiceRequestReader reader;
    private final SigningPolicy policy;
    private final Signer signer;
    private final AuditLog auditLog;

    public SignServiceRequest(ServiceRequestReader reader, SigningPolicy policy, Signer signer, AuditLog auditLog) {
        this.reader = reader;
        this.policy = policy;
        this.signer = signer;
        this.auditLog = auditLog;
    }

    public SignResult sign(String unsignedXml) {
        ServiceRequest request;
        try {
            request = reader.read(unsignedXml);
        } catch (InvalidServiceRequestException e) {
            return refuse(null, null, "invalid Service Request: " + e.getMessage());
        }

        return switch (policy.decide(request)) {
            case SigningDecision.Refused refused -> refuse(request.requestId(), request.srv(), refused.reason());
            case SigningDecision.Approved approved -> {
                String signedXml = signer.sign(request.xml(), approved.role());
                auditLog.record(new AuditEvent(request.requestId(), request.srv(), Outcome.SIGNED, approved.role(), null));
                yield new SignResult.Signed(request.requestId(), approved.role(), signedXml);
            }
        };
    }

    private SignResult refuse(String requestId, String srv, String reason) {
        auditLog.record(new AuditEvent(requestId, srv, Outcome.REFUSED, null, reason));
        return new SignResult.Refused(requestId, reason);
    }
}
