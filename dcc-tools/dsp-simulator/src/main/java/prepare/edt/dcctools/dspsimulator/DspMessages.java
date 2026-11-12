package prepare.edt.dcctools.dspsimulator;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Reads Service Requests and writes the DSP's messages, in the PLACEHOLDER format (urn:edt:placeholder:duis),
 * not real DUIS. Response codes (I0 accepted, E99 rejected) are placeholders too.
 * The DSP signs them with DspSigner; the EDT checks that signature.
 */
final class DspMessages {

    static final String NAMESPACE = "urn:edt:placeholder:duis";
    static final String XMLDSIG = "http://www.w3.org/2000/09/xmldsig#";

    private DspMessages() {
    }

    /** The values the simulator needs from a signed Service Request. */
    record ServiceRequest(String requestId, String targetDevice, boolean signed) {
    }

    static ServiceRequest read(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        return new ServiceRequest(
                text(document, "RequestId"),
                text(document, "Target"),
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

    private static String text(Document document, String name) {
        var nodes = document.getElementsByTagNameNS(NAMESPACE, name);
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
