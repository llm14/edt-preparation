package prepare.edt.orchestrator.adapter;

/**
 * How one Service Request ended. Returned to the Flow, which decides whether to continue.
 *
 * Statuses:
 *   RESPONDED          the device answered: responseXml holds the Response
 *   REFUSED_BY_SIGNER  the DUIS Signer refused to sign it (detail says why); it was never sent
 *   REJECTED_BY_DSP    the synchronous DUIS Response rejected it (detail says why)
 *   SEND_FAILED        the post to the DSP HTTPS Gateway failed (the gateway already retried)
 *   FAILED_ALERT       a DCC Alert N12 or N13 arrived (alertCode)
 *   TIMED_OUT          no Response within the configured timeout
 * Only RESPONDED is a success. None of the failures is ever sent again (architecture model).
 */
public record ServiceRequestOutcome(String status, String requestId, String responseXml, String alertCode,
                                    String detail) {

    public static final String RESPONDED = "RESPONDED";
    public static final String REFUSED_BY_SIGNER = "REFUSED_BY_SIGNER";
    public static final String REJECTED_BY_DSP = "REJECTED_BY_DSP";
    public static final String SEND_FAILED = "SEND_FAILED";
    public static final String FAILED_ALERT = "FAILED_ALERT";
    public static final String TIMED_OUT = "TIMED_OUT";
}
