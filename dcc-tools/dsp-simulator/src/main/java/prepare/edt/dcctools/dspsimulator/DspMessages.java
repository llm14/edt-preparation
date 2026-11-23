package prepare.edt.dcctools.dspsimulator;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Reads Service Requests, which are real DUIS 5.5 requests, and writes the DSP's messages, which are still in the
 * PLACEHOLDER format (urn:edt:placeholder:duis), not real DUIS: the synchronous response, the Response and the DCC Alert.
 * Response codes (I0 accepted, E99 rejected) are placeholders too.
 * The DSP signs them with DspSigner; the EDT checks that signature.
 */
final class DspMessages {

    /** The namespace of the placeholder messages the simulator writes. */
    static final String NAMESPACE = "urn:edt:placeholder:duis";
    /** The namespace of the real DUIS requests the simulator reads. */
    static final String REQUEST_NAMESPACE = "http://www.dccinterface.co.uk/ServiceUserGateway";
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

    static String response(String requestId) {
        return "<Response xmlns=\"" + NAMESPACE + "\">"
                + element("RequestId", requestId)
                + element("ResponseCode", "I0")
                + "<Body>" + element("InstantaneousImportRegister", "12345.6") + "</Body>"
                + "</Response>";
    }

    static String dccAlert(String requestId, String alertCode) {
        return "<DCCAlert xmlns=\"" + NAMESPACE + "\">"
                + element("AlertCode", alertCode)
                + element("RequestId", requestId)
                + "</DCCAlert>";
    }

    /**
     * Changes a signed message the way an attacker or a fault might: the response code I0 becomes I99.
     * The signature no longer matches, so the EDT must not trust the message.
     */
    static String tamper(String signedXml) {
        String tampered = signedXml.replaceFirst("<ResponseCode>I0</ResponseCode>", "<ResponseCode>I99</ResponseCode>");
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
