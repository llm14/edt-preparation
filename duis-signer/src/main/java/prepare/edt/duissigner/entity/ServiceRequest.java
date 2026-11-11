package prepare.edt.duissigner.entity;

import java.util.Objects;

/**
 * An unsigned Service Request, with the values the signing policy decides on.
 *
 * @param requestId  the DUIS Request ID
 * @param srv        the Service Reference Variant: which kind of Service Request it is
 * @param originator the User ID that sends it
 * @param deviceType the type of the target device (for example ESME, GSME, CHF)
 * @param xml        the unsigned XML, exactly as it will be signed
 */
public record ServiceRequest(String requestId, String srv, String originator, String deviceType, String xml) {

    public ServiceRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(srv, "srv");
        Objects.requireNonNull(originator, "originator");
        Objects.requireNonNull(deviceType, "deviceType");
        Objects.requireNonNull(xml, "xml");
    }
}
