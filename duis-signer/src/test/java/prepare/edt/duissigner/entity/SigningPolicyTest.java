package prepare.edt.duissigner.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import prepare.edt.duissigner.entity.SigningPolicy.RolePolicy;

/** PLACEHOLDER data: SRV codes, User IDs and allowlists are invented until DCC provides the real ones. */
class SigningPolicyTest {

    static final String OTHER_USER_ID = "90-B3-D5-1F-30-01-00-01";
    static final String RSA_USER_ID = "90-B3-D5-1F-30-01-00-02";

    static final SigningPolicy POLICY = new SigningPolicy(Map.of(
            UserRole.OTHER_USER, new RolePolicy(OTHER_USER_ID, Map.of(
                    "4.1.1", Set.of("ESME", "GSME"),          // both roles may send 4.1.1
                    "8.2", Set.of("CHF"))),
            UserRole.REGISTERED_SUPPLIER_AGENT, new RolePolicy(RSA_USER_ID, Map.of(
                    "4.1.1", Set.of("ESME", "GSME"),
                    "6.15.1", Set.of("ESME")))));               // only the Registered Supplier Agent

    @Test
    void srv_allowed_for_both_roles_is_signed_as_other_user() {
        assertEquals(new SigningDecision.Approved(UserRole.OTHER_USER),
                POLICY.decide(request("4.1.1", OTHER_USER_ID, "ESME")));
    }

    @Test
    void srv_allowed_only_for_registered_supplier_agent_is_signed_as_that_role() {
        assertEquals(new SigningDecision.Approved(UserRole.REGISTERED_SUPPLIER_AGENT),
                POLICY.decide(request("6.15.1", RSA_USER_ID, "ESME")));
    }

    @Test
    void srv_on_no_allowlist_is_refused() {
        assertEquals(new SigningDecision.Refused("SRV 1.1.1 is not on the allowlist of any User Role"),
                POLICY.decide(request("1.1.1", OTHER_USER_ID, "ESME")));
    }

    @Test
    void originator_that_is_not_the_chosen_role_is_refused() {
        // 4.1.1 is sent as Other User, so the Registered Supplier Agent's User ID does not match.
        assertEquals(new SigningDecision.Refused("originator " + RSA_USER_ID + " is not the User ID of OTHER_USER"),
                POLICY.decide(request("4.1.1", RSA_USER_ID, "ESME")));
    }

    @Test
    void device_type_not_allowed_for_the_srv_is_refused() {
        assertEquals(new SigningDecision.Refused("device type CHF is not allowed for SRV 4.1.1"),
                POLICY.decide(request("4.1.1", OTHER_USER_ID, "CHF")));
    }

    @Test
    void the_first_failure_wins() {
        // Both the originator and the device type are wrong: the originator is checked first.
        assertEquals(new SigningDecision.Refused("originator someone-else is not the User ID of OTHER_USER"),
                POLICY.decide(request("4.1.1", "someone-else", "CHF")));
    }

    static ServiceRequest request(String srv, String originator, String deviceType) {
        return new ServiceRequest("REQ-1", srv, originator, deviceType, "<ServiceRequest/>");
    }
}
