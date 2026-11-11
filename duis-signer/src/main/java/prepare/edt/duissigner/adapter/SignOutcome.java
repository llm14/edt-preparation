package prepare.edt.duissigner.adapter;

/**
 * What the Sign activity returns, as the model says: the signed Service Request, or the reason it was refused.
 * Temporal sends it as JSON, so the field names are the contract with the Orchestrator's copy of this record.
 * Plain Strings, not enums, so a new value never breaks the other side's deserialisation.
 *
 * @param status               SIGNED or REFUSED
 * @param requestId            the DUIS Request ID (null if the XML could not be read)
 * @param userRole             OTHER_USER or REGISTERED_SUPPLIER_AGENT when signed, otherwise null
 * @param signedServiceRequest the signed XML when signed, otherwise null
 * @param refusalReason        why it was refused, otherwise null
 */
public record SignOutcome(String status, String requestId, String userRole, String signedServiceRequest,
                          String refusalReason) {

    public static final String SIGNED = "SIGNED";
    public static final String REFUSED = "REFUSED";
}
