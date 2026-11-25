package prepare.edt.duissigner.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import prepare.edt.duissigner.entity.SigningPolicy;
import prepare.edt.duissigner.entity.SigningPolicy.RolePolicy;
import prepare.edt.duissigner.entity.UserRole;
import prepare.edt.duissigner.usecase.SignServiceRequest;

/** The activity turns the use case's result into the SignOutcome the Orchestrator receives. PLACEHOLDER policy data. */
class SignActivityImplTest {

    static final String REQUEST_ID = "90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A0:1000";

    final SigningPolicy policy = new SigningPolicy(Map.of(UserRole.OTHER_USER,
            new RolePolicy("90-B3-D5-1F-30-01-00-01", Map.of("4.1.1", Set.of("ESME")))));

    final SignActivityImpl activity = new SignActivityImpl(new SignServiceRequest(
            new XmlServiceRequestReader(), policy, (xml, role) -> "signed-" + role, event -> { }));

    @Test
    void a_signed_request_becomes_status_signed() {
        SignOutcome outcome = activity.sign(JcaXmlSignerTest.UNSIGNED, "ESME");

        assertEquals(new SignOutcome("SIGNED", REQUEST_ID, "OTHER_USER", "signed-OTHER_USER", null), outcome);
    }

    @Test
    void a_refused_request_is_a_normal_result_with_the_reason() {
        // Valid against the schema, but SRV 8.9 is not on this policy's allowlist.
        String srv89 = XmlServiceRequestReaderTest.SRV_8_9;

        SignOutcome outcome = activity.sign(srv89, "CHF");

        assertEquals(new SignOutcome("REFUSED", "90-B3-D5-1F-30-01-00-01:00-DB-12-34-56-78-90-A2:1000", null, null,
                "SRV 8.9 is not on the allowlist of any User Role"), outcome);
    }

    @Test
    void the_device_type_given_with_the_request_is_what_the_policy_checks() {
        SignOutcome outcome = activity.sign(JcaXmlSignerTest.UNSIGNED, "CHF");

        assertEquals(new SignOutcome("REFUSED", REQUEST_ID, null, null,
                "device type CHF is not allowed for SRV 4.1.1"), outcome);
    }

    @Test
    void a_request_that_is_not_valid_is_refused_with_the_schema_reason() {
        SignOutcome outcome = activity.sign("<sr:Request>", "ESME");

        assertEquals("REFUSED", outcome.status());
        assertEquals(true, outcome.refusalReason().startsWith("invalid Service Request: "), outcome.refusalReason());
    }
}
