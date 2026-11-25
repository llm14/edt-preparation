package prepare.edt.orchestrator.adapter;

import io.temporal.failure.ApplicationFailure;
import java.util.Map;

/**
 * Builds a DUIS 5.5 Service Request, in the shape of the released examples (RTDS 4.10.0): a Request in the ServiceUserGateway
 * namespace whose Header has the Request ID, the Command Variant and the Service Reference (and its variant), and whose Body
 * is one empty request element.
 *
 * The request names no originator and no device type: both are in the Request ID ("originator:device:counter").
 * The Command Variant is 1, the value of the non-critical read requests of the released data.
 *
 * Only the SRVs below are known: 4.1.1 and 8.9, and the three of the draft Flows (6.2.9, 6.30, 11.2). The DUIS Signer's
 * partial schema lists the same five. An SRV that is not in the table fails without retry: building it again gives the same result.
 */
public final class RequestBuilderActivityImpl implements RequestBuilderActivity {

    static final String NAMESPACE = "http://www.dccinterface.co.uk/ServiceUserGateway";
    static final String SCHEMA_VERSION = "5.5";
    static final String COMMAND_VARIANT = "1";
    static final String UNKNOWN_SRV = "UnknownSrv";

    /** What an SRV needs in the request: its Service Reference and the element of its Body. */
    private record SrvShape(String serviceReference, String bodyElement) {
    }

    private static final Map<String, SrvShape> SRVS = Map.of(
            "4.1.1", new SrvShape("4.1", "ReadInstantaneousImportRegisters"),
            "8.9", new SrvShape("8.9", "ReadDeviceLog"),
            "6.2.9", new SrvShape("6.2", "ReadDeviceConfigurationPaymentMode"),
            "6.30", new SrvShape("6.30", "ReadCHFSubGHzConfiguration"),
            "11.2", new SrvShape("11.2", "ReadFirmwareVersion"));

    @Override
    public String build(ServiceRequestInput input) {
        SrvShape shape = SRVS.get(input.srv());
        if (shape == null) {
            throw ApplicationFailure.newNonRetryableFailure("no Service Request is defined for SRV " + input.srv(), UNKNOWN_SRV);
        }
        return "<sr:Request xmlns:sr=\"" + NAMESPACE + "\" schemaVersion=\"" + SCHEMA_VERSION + "\">"
                + "<sr:Header>"
                + element("RequestID", input.requestId())
                + element("CommandVariant", COMMAND_VARIANT)
                + element("ServiceReference", shape.serviceReference())
                + element("ServiceReferenceVariant", input.srv())
                + "</sr:Header>"
                + "<sr:Body><sr:" + shape.bodyElement() + "/></sr:Body>"
                + "</sr:Request>";
    }

    private static String element(String name, String value) {
        return "<sr:" + name + ">" + escape(value) + "</sr:" + name + ">";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
