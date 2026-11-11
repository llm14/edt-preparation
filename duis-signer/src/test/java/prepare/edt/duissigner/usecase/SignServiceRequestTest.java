package prepare.edt.duissigner.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import prepare.edt.duissigner.entity.ServiceRequest;
import prepare.edt.duissigner.entity.SigningPolicy;
import prepare.edt.duissigner.entity.SigningPolicy.RolePolicy;
import prepare.edt.duissigner.entity.UserRole;
import prepare.edt.duissigner.usecase.AuditLog.AuditEvent;
import prepare.edt.duissigner.usecase.AuditLog.Outcome;

/**
 * Tests the use case with hand-written fakes for its three ports. No framework, no XML, no keys.
 * PLACEHOLDER data: invented SRV and User ID.
 */
class SignServiceRequestTest {

    static final String OTHER_USER_ID = "90-B3-D5-1F-30-01-00-01";

    final SigningPolicy policy = new SigningPolicy(Map.of(
            UserRole.OTHER_USER, new RolePolicy(OTHER_USER_ID, Map.of("4.1.1", Set.of("ESME")))));

    /** Fake reader: the "XML" is just "srv|originator|deviceType", or "bad" for an invalid one. */
    final ServiceRequestReader reader = xml -> {
        if (xml.equals("bad")) {
            throw new ServiceRequestReader.InvalidServiceRequestException("not valid against the schema");
        }
        String[] parts = xml.split("\\|");
        return new ServiceRequest("REQ-1", parts[0], parts[1], parts[2], xml);
    };

    /** Fake signer: remembers its calls and "signs" by adding a marker. */
    final List<UserRole> signedAs = new ArrayList<>();
    final Signer signer = (xml, role) -> {
        signedAs.add(role);
        return xml + "|signed-as-" + role;
    };

    /** Fake audit log: keeps the events in a list. */
    final List<AuditEvent> audit = new ArrayList<>();

    final SignServiceRequest useCase = new SignServiceRequest(reader, policy, signer, audit::add);

    @Test
    void approved_request_is_signed_and_audited() {
        SignResult result = useCase.sign("4.1.1|" + OTHER_USER_ID + "|ESME");

        assertEquals(new SignResult.Signed("REQ-1", UserRole.OTHER_USER,
                "4.1.1|" + OTHER_USER_ID + "|ESME|signed-as-OTHER_USER"), result);
        assertEquals(List.of(new AuditEvent("REQ-1", "4.1.1", Outcome.SIGNED, UserRole.OTHER_USER, null)), audit);
    }

    @Test
    void refused_request_is_not_signed_but_audited() {
        SignResult result = useCase.sign("9.9.9|" + OTHER_USER_ID + "|ESME");

        String reason = "SRV 9.9.9 is not on the allowlist of any User Role";
        assertEquals(new SignResult.Refused("REQ-1", reason), result);
        assertTrue(signedAs.isEmpty(), "a refused request must never reach the signer");
        assertEquals(List.of(new AuditEvent("REQ-1", "9.9.9", Outcome.REFUSED, null, reason)), audit);
    }

    @Test
    void invalid_xml_is_refused_and_audited() {
        SignResult result = useCase.sign("bad");

        String reason = "invalid Service Request: not valid against the schema";
        assertEquals(new SignResult.Refused(null, reason), result);
        assertTrue(signedAs.isEmpty());
        assertEquals(List.of(new AuditEvent(null, null, Outcome.REFUSED, null, reason)), audit);
    }

    @Test
    void no_signed_request_is_returned_if_the_audit_fails() {
        AuditLog brokenAudit = event -> {
            throw new IllegalStateException("audit log unavailable");
        };
        SignServiceRequest withBrokenAudit = new SignServiceRequest(reader, policy, signer, brokenAudit);

        assertThrows(IllegalStateException.class, () -> withBrokenAudit.sign("4.1.1|" + OTHER_USER_ID + "|ESME"));
    }

    @Test
    void a_signing_error_propagates_so_temporal_can_retry() {
        Signer down = (xml, role) -> {
            throw new IllegalStateException("signing service unavailable");
        };
        SignServiceRequest withSignerDown = new SignServiceRequest(reader, policy, down, audit::add);

        assertThrows(IllegalStateException.class, () -> withSignerDown.sign("4.1.1|" + OTHER_USER_ID + "|ESME"));
        assertTrue(audit.isEmpty(), "a technical error is not a decision, so it is not audited");
    }
}
