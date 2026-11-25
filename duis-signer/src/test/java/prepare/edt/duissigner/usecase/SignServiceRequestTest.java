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
 * PLACEHOLDER policy data: invented User ID.
 */
class SignServiceRequestTest {

    static final String OTHER_USER_ID = "90-B3-D5-1F-30-01-00-01";

    final SigningPolicy policy = new SigningPolicy(Map.of(
            UserRole.OTHER_USER, new RolePolicy(OTHER_USER_ID, Map.of("4.1.1", Set.of("ESME")))));

    /**
     * Fake reader: the "XML" is just "srv|originator", or "bad" for an invalid one.
     * The device type is the one given with the request, as in the real reader.
     */
    final ServiceRequestReader reader = (xml, deviceType) -> {
        if (xml.equals("bad")) {
            throw new ServiceRequestReader.InvalidServiceRequestException("not valid against the schema");
        }
        String[] parts = xml.split("\\|");
        return new ServiceRequest("REQ-1", parts[0], parts[1], deviceType, xml);
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

    /** The recorded events with the duration set to 0, so they can be compared with equals. */
    List<AuditEvent> withoutDurations() {
        return audit.stream().map(e -> new AuditEvent(e.requestId(), e.srv(), e.outcome(), e.role(), e.reason(), 0)).toList();
    }

    @Test
    void every_decision_records_how_long_it_took() {
        useCase.sign("4.1.1|" + OTHER_USER_ID, "ESME");
        useCase.sign("9.9.9|" + OTHER_USER_ID, "ESME");
        useCase.sign("bad", "ESME");

        assertEquals(3, audit.size());
        assertTrue(audit.stream().allMatch(e -> e.durationNanos() >= 0), "a signed, a refused and an invalid request");
    }

    @Test
    void approved_request_is_signed_and_audited() {
        SignResult result = useCase.sign("4.1.1|" + OTHER_USER_ID, "ESME");

        assertEquals(new SignResult.Signed("REQ-1", UserRole.OTHER_USER,
                "4.1.1|" + OTHER_USER_ID + "|signed-as-OTHER_USER"), result);
        assertEquals(List.of(new AuditEvent("REQ-1", "4.1.1", Outcome.SIGNED, UserRole.OTHER_USER, null, 0)), withoutDurations());
    }

    @Test
    void refused_request_is_not_signed_but_audited() {
        SignResult result = useCase.sign("9.9.9|" + OTHER_USER_ID, "ESME");

        String reason = "SRV 9.9.9 is not on the allowlist of any User Role";
        assertEquals(new SignResult.Refused("REQ-1", reason), result);
        assertTrue(signedAs.isEmpty(), "a refused request must never reach the signer");
        assertEquals(List.of(new AuditEvent("REQ-1", "9.9.9", Outcome.REFUSED, null, reason, 0)), withoutDurations());
    }

    @Test
    void the_device_type_given_with_the_request_decides_the_policy_check() {
        SignResult result = useCase.sign("4.1.1|" + OTHER_USER_ID, "CHF");

        assertEquals(new SignResult.Refused("REQ-1", "device type CHF is not allowed for SRV 4.1.1"), result);
        assertTrue(signedAs.isEmpty());
    }

    @Test
    void invalid_xml_is_refused_and_audited() {
        SignResult result = useCase.sign("bad", "ESME");

        String reason = "invalid Service Request: not valid against the schema";
        assertEquals(new SignResult.Refused(null, reason), result);
        assertTrue(signedAs.isEmpty());
        assertEquals(List.of(new AuditEvent(null, null, Outcome.REFUSED, null, reason, 0)), withoutDurations());
    }

    @Test
    void no_signed_request_is_returned_if_the_audit_fails() {
        AuditLog brokenAudit = event -> {
            throw new IllegalStateException("audit log unavailable");
        };
        SignServiceRequest withBrokenAudit = new SignServiceRequest(reader, policy, signer, brokenAudit);

        assertThrows(IllegalStateException.class, () -> withBrokenAudit.sign("4.1.1|" + OTHER_USER_ID, "ESME"));
    }

    @Test
    void a_signing_error_propagates_so_temporal_can_retry() {
        Signer down = (xml, role) -> {
            throw new IllegalStateException("signing service unavailable");
        };
        SignServiceRequest withSignerDown = new SignServiceRequest(reader, policy, down, audit::add);

        assertThrows(IllegalStateException.class, () -> withSignerDown.sign("4.1.1|" + OTHER_USER_ID, "ESME"));
        assertTrue(audit.isEmpty(), "a technical error is not a decision, so it is not audited");
    }
}
