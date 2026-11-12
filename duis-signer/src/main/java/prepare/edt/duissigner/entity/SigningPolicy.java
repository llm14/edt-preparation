package prepare.edt.duissigner.entity;

import java.util.Map;
import java.util.Set;

/**
 * Which Service Requests each User Role may send, and the rule that picks the role.
 *
 * Checks, stopping at the first failure (from the architecture model):
 *   1. the SRV is on the allowlist of at least one role,
 *   2. the role is chosen: Other User when both roles may send the SRV,
 *   3. the originator is the User ID of the chosen role,
 *   4. the device type is allowed for the SRV in that role.
 * (The DUIS schema check happens before, when the XML is read.)
 *
 * Interpretation, to confirm with DCC: the role is chosen from the SRV only,
 * then the originator must match it. A request whose originator is the other role's User ID is refused.
 */
public final class SigningPolicy {

    /**
     * What one role may do.
     *
     * @param userId          the role's DUIS User ID, which must be the originator
     * @param allowedSrvs     SRV to the device types allowed for it
     */
    public record RolePolicy(String userId, Map<String, Set<String>> allowedSrvs) {

        public RolePolicy {
            allowedSrvs = Map.copyOf(allowedSrvs);
        }

        boolean allows(String srv) {
            return allowedSrvs.containsKey(srv);
        }
    }

    private final Map<UserRole, RolePolicy> roles;

    public SigningPolicy(Map<UserRole, RolePolicy> roles) {
        this.roles = Map.copyOf(roles);
    }

    public SigningDecision decide(ServiceRequest request) {
        UserRole role = chooseRole(request.srv());
        if (role == null) {
            return new SigningDecision.Refused("SRV " + request.srv() + " is not on the allowlist of any User Role");
        }

        RolePolicy policy = roles.get(role);
        if (!policy.userId().equals(request.originator())) {
            return new SigningDecision.Refused(
                    "originator " + request.originator() + " is not the User ID of " + role);
        }
        if (!policy.allowedSrvs().get(request.srv()).contains(request.deviceType())) {
            return new SigningDecision.Refused(
                    "device type " + request.deviceType() + " is not allowed for SRV " + request.srv());
        }
        return new SigningDecision.Approved(role);
    }

    /** Other User when it may send the SRV (alone or with the other role), otherwise Registered Supplier Agent. */
    private UserRole chooseRole(String srv) {
        if (allows(UserRole.OTHER_USER, srv)) {
            return UserRole.OTHER_USER;
        }
        if (allows(UserRole.REGISTERED_SUPPLIER_AGENT, srv)) {
            return UserRole.REGISTERED_SUPPLIER_AGENT;
        }
        return null;
    }

    private boolean allows(UserRole role, String srv) {
        RolePolicy policy = roles.get(role);
        return policy != null && policy.allows(srv);
    }
}
