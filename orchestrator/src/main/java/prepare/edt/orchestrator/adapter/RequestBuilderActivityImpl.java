package prepare.edt.orchestrator.adapter;

/**
 * Builds the PLACEHOLDER XML format the DUIS Signer validates (urn:edt:placeholder:duis), not real DUIS.
 * Replace it with the real DUIS builder (the DUIS Library) when available.
 */
public final class RequestBuilderActivityImpl implements RequestBuilderActivity {

    @Override
    public String build(ServiceRequestInput input) {
        return "<ServiceRequest xmlns=\"urn:edt:placeholder:duis\">"
                + "<Header>"
                + element("RequestId", input.requestId())
                + element("Originator", input.originator())
                + element("SRV", input.srv())
                + element("DeviceType", input.deviceType())
                + "</Header>"
                + "<Body>" + element("Target", input.targetDevice()) + "</Body>"
                + "</ServiceRequest>";
    }

    private static String element(String name, String value) {
        return "<" + name + ">" + escape(value) + "</" + name + ">";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
