package prepare.edt.orchestrator.adapter;

import prepare.edt.orchestrator.entity.Facts;

/**
 * How one Service Request ended: the outcome and the Facts (model 010.030, step 22). Returned to the Flow,
 * which publishes the step and decides whether to continue.
 *
 * Statuses:
 *   RESPONDED          the device answered and the Response was decoded: facts holds the Facts
 *   DECODE_FAILED      the device answered, but the Response could not be decoded (detail says why)
 *   REFUSED_BY_SIGNER  the DUIS Signer refused to sign it (detail says why); it was never sent
 *   REJECTED_BY_DSP    the synchronous DUIS Response rejected it (detail says why)
 *   SEND_FAILED        the post to the DSP HTTPS Gateway failed (the gateway already retried)
 *   FAILED_ALERT       a DCC Alert N12 or N13 arrived (alertCode)
 *   TIMED_OUT          no Response within the configured timeout
 * Only RESPONDED is a success. None of the failures is ever sent again (architecture model).
 * The Response itself is not returned: the Flow decides on the Facts, and Temporal histories stay small.
 */
public record ServiceRequestOutcome(String status, String requestId, Facts facts, String alertCode,
                                    String detail) {

    public static final String RESPONDED = "RESPONDED";
    public static final String DECODE_FAILED = "DECODE_FAILED";
    public static final String REFUSED_BY_SIGNER = "REFUSED_BY_SIGNER";
    public static final String REJECTED_BY_DSP = "REJECTED_BY_DSP";
    public static final String SEND_FAILED = "SEND_FAILED";
    public static final String FAILED_ALERT = "FAILED_ALERT";
    public static final String TIMED_OUT = "TIMED_OUT";
}
