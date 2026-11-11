package prepare.edt.duissigner.entity;

/**
 * The outcome of the signing policy: sign as a User Role, or refuse with a reason.
 * A sealed interface: these two records are the only possible decisions.
 */
public sealed interface SigningDecision {

    record Approved(UserRole role) implements SigningDecision {
    }

    record Refused(String reason) implements SigningDecision {
    }
}
