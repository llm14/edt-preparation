package prepare.edt.duissigner.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import prepare.edt.duissigner.entity.SigningPolicy;
import prepare.edt.duissigner.entity.SigningPolicy.RolePolicy;
import prepare.edt.duissigner.entity.UserRole;
import prepare.edt.duissigner.usecase.SignServiceRequest;

/** The activity turns the use case's result into the SignOutcome the Orchestrator receives. PLACEHOLDER data. */
class SignActivityImplTest {

    final SigningPolicy policy = new SigningPolicy(Map.of(UserRole.OTHER_USER,
            new RolePolicy("90-B3-D5-1F-30-01-00-01", Map.of("4.1.1", Set.of("ESME")))));

    final SignActivityImpl activity = new SignActivityImpl(new SignServiceRequest(
            new XmlServiceRequestReader(), policy, (xml, role) -> "signed-" + role, event -> { }));

    @Test
    void a_signed_request_becomes_status_signed() {
        SignOutcome outcome = activity.sign(JcaXmlSignerTest.UNSIGNED);

        assertEquals(new SignOutcome("SIGNED", "REQ-1", "OTHER_USER", "signed-OTHER_USER", null), outcome);
    }

    @Test
    void a_refused_request_is_a_normal_result_with_the_reason() {
        SignOutcome outcome = activity.sign(JcaXmlSignerTest.UNSIGNED.replace("4.1.1", "9.9.9"));

        assertEquals(new SignOutcome("REFUSED", "REQ-1", null, null,
                "SRV 9.9.9 is not on the allowlist of any User Role"), outcome);
    }
}
