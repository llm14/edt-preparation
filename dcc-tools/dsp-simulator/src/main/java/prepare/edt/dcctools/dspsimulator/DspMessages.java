package prepare.edt.dcctools.dspsimulator;

import java.io.StringReader;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Reads Service Requests, which are real DUIS 5.5 requests, and writes the Response in the real decoded format (MMC 5.5,
 * written from the RTDS 4.10.0 examples ECS17b, GCS13a and CCS06). The synchronous response and the DCC Alert are still in
 * the PLACEHOLDER format (urn:edt:placeholder:duis), not real DUIS; response codes (I0 accepted, E99 rejected) are placeholders too.
 * The DSP signs them with DspSigner; the EDT checks that signature.
 */
final class DspMessages {

    /** The namespace of the placeholder messages the simulator writes. */
    static final String NAMESPACE = "urn:edt:placeholder:duis";
    /** The namespace of the real DUIS requests the simulator reads. */
    static final String REQUEST_NAMESPACE = "http://www.dccinterface.co.uk/ServiceUserGateway";
    /** The namespace of the real Response the simulator writes. */
    static final String RESPONSE_NAMESPACE = "http://www.dccinterface.co.uk/ResponseAndAlert";
    /** The Access Control Broker, the target of a Response that comes through a Communications Hub (as in RTDS CCS06). */
    static final String ACCESS_CONTROL_BROKER = "90-B3-D5-1F-30-00-00-02";
    static final String XMLDSIG = "http://www.w3.org/2000/09/xmldsig#";

    private DspMessages() {
    }

    /**
     * The values the simulator needs from a signed Service Request.
     *
     * @param requestId    the Request ID, "originator:device:counter"
     * @param targetDevice the device: the middle part of the Request ID (the request has no other place for it)
     * @param srv          the Service Reference Variant of the request
     * @param signed       whether the request carries exactly one XML signature
     */
    record ServiceRequest(String requestId, String targetDevice, String srv, boolean signed) {
        /** The part of the Request ID before the first colon. */
        String originator() {
            return requestId.split(":")[0];
        }

        /** The part of the Request ID after the last colon. */
        String counter() {
            return requestId.split(":")[2];
        }
    }

    static ServiceRequest read(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        String requestId = text(document, REQUEST_NAMESPACE, "RequestID");
        String[] parts = requestId.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("RequestID is not originator:device:counter: " + requestId);
        }
        return new ServiceRequest(
                requestId,
                parts[1],
                text(document, REQUEST_NAMESPACE, "ServiceReferenceVariant"),
                document.getElementsByTagNameNS(XMLDSIG, "Signature").getLength() == 1);
    }

    static String syncResponse(String requestId, boolean accepted, String detail) {
        return "<SyncResponse xmlns=\"" + NAMESPACE + "\">"
                + element("RequestId", requestId)
                + element("ResponseCode", accepted ? "I0" : "E99")
                + element("Detail", detail)
                + "</SyncResponse>";
    }

    /**
     * The real Response to a request. The header is built from the request: the device answers, to the originator, with the
     * request's counter. For SRV 8.9 (a device log, answered by the hub) the target is the Access Control Broker and the
     * supplementary fields carry the original originator and counter, as in RTDS CCS06. For 4.1.1 the device is a gas
     * meter when it is in gasDevices, an electricity meter otherwise.
     */
    static String response(ServiceRequest request, Set<String> gasDevices) {
        boolean deviceLog = "8.9".equals(request.srv());
        String header = "<ra:BusinessOriginatorID>" + request.targetDevice() + "</ra:BusinessOriginatorID>"
                + (deviceLog
                        ? "<ra:BusinessTargetID>" + ACCESS_CONTROL_BROKER + "</ra:BusinessTargetID>"
                                + "<ra:OriginatorCounter>1300</ra:OriginatorCounter>"
                                + "<ra:SupplementaryRemotePartyID>" + request.originator() + "</ra:SupplementaryRemotePartyID>"
                                + "<ra:SupplementaryRemotePartyCounter>" + request.counter() + "</ra:SupplementaryRemotePartyCounter>"
                        : "<ra:BusinessTargetID>" + request.originator() + "</ra:BusinessTargetID>"
                                + "<ra:OriginatorCounter>" + request.counter() + "</ra:OriginatorCounter>")
                + "<ra:ServiceReference>" + serviceReference(request.srv()) + "</ra:ServiceReference>"
                + "<ra:ServiceReferenceVariant>" + request.srv() + "</ra:ServiceReferenceVariant>";
        String content = switch (request.srv()) {
            case "8.9" -> deviceLog();
            default -> gasDevices.contains(request.targetDevice()) ? gasRegisters() : electricityRegisters();
        };
        return "<ra:GBCSResponse xmlns:ra=\"" + RESPONSE_NAMESPACE + "\" schemaVersion=\"5.5\">"
                + "<ra:Header>" + header + "</ra:Header>"
                + "<ra:Body><ra:ResponseMessage><ra:SMETSData>" + content + "</ra:SMETSData></ra:ResponseMessage></ra:Body>"
                + "</ra:GBCSResponse>";
    }

    /** The Service Reference of a variant: its first two parts (4.1.1 is 4.1, 8.9 is 8.9). */
    private static String serviceReference(String srv) {
        String[] parts = srv.split("[.]");
        return parts.length < 2 ? srv : parts[0] + "." + parts[1];
    }

    private static String electricityRegisters() {
        return "<ra:ReadInstantaneousImportRegistersRsp MessageSuccess=\"true\"><ra:Electricity>"
                + "<ra:ActiveImportRegister><ra:Value>10</ra:Value><ra:ActiveEnergyUnit>Wh</ra:ActiveEnergyUnit></ra:ActiveImportRegister>"
                + "<ra:ReactiveImportRegister><ra:Value>10</ra:Value><ra:ReactiveEnergyUnit>varh</ra:ReactiveEnergyUnit></ra:ReactiveImportRegister>"
                + "</ra:Electricity></ra:ReadInstantaneousImportRegistersRsp>";
    }

    private static String gasRegisters() {
        return "<ra:ReadInstantaneousImportRegistersRsp MessageSuccess=\"true\"><ra:Gas>"
                + "<ra:ConsumptionRegister><ra:Value>12.345</ra:Value><ra:Unit>m3</ra:Unit></ra:ConsumptionRegister>"
                + "</ra:Gas></ra:ReadInstantaneousImportRegistersRsp>";
    }

    private static String deviceLog() {
        return "<ra:ReadDeviceLogRsp MessageSuccess=\"true\"><ra:CHFDeviceLog>"
                + logEntry("00-DB-12-34-56-78-90-A0", "2014-12-31T23:50:00.00Z", "-50")
                + logEntry("00-DB-12-34-56-78-90-A1", "2014-12-31T23:40:00.00Z", "-54")
                + "</ra:CHFDeviceLog></ra:ReadDeviceLogRsp>";
    }

    private static String logEntry(String deviceId, String lastCommunication, String linkQuality) {
        return "<ra:CHFDeviceLogEntry><ra:DeviceID>" + deviceId + "</ra:DeviceID>"
                + "<ra:LastCommunicationsDateTime>" + lastCommunication + "</ra:LastCommunicationsDateTime>"
                + "<ra:SubGHzLinkQuality>" + linkQuality + "</ra:SubGHzLinkQuality></ra:CHFDeviceLogEntry>";
    }

    static String dccAlert(String requestId, String alertCode) {
        return "<DCCAlert xmlns=\"" + NAMESPACE + "\">"
                + element("AlertCode", alertCode)
                + element("RequestId", requestId)
                + "</DCCAlert>";
    }

    /**
     * Changes a signed message the way an attacker or a fault might: in a Response MessageSuccess "true" becomes "false",
     * in the placeholder synchronous response the code I0 becomes I99. The signature no longer matches, so the EDT
     * must not trust the message.
     */
    static String tamper(String signedXml) {
        String tampered = signedXml.contains("MessageSuccess=\"true\"")
                ? signedXml.replaceFirst("MessageSuccess=\"true\"", "MessageSuccess=\"false\"")
                : signedXml.replaceFirst("<ResponseCode>I0</ResponseCode>", "<ResponseCode>I99</ResponseCode>");
        if (tampered.equals(signedXml)) {
            throw new IllegalArgumentException("nothing to tamper with");
        }
        return tampered;
    }

    private static String text(Document document, String namespace, String name) {
        var nodes = document.getElementsByTagNameNS(namespace, name);
        if (nodes.getLength() == 0) {
            throw new IllegalArgumentException("missing element " + name);
        }
        return nodes.item(0).getTextContent().trim();
    }

    private static String element(String name, String value) {
        String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<" + name + ">" + escaped + "</" + name + ">";
    }
}
