package prepare.edt.responselistener.adapter;

import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import prepare.edt.responselistener.entity.DspMessage;
import prepare.edt.responselistener.usecase.DspMessageReader;

/**
 * Reads what the DSP sends. A Response is the real decoded message (MMC 5.5): a GBCSResponse in the ResponseAndAlert
 * namespace whose Body holds a ResponseMessage. It carries no Request ID, so it is rebuilt from the header
 * (design.md D6). A DCC Alert is still read in the PLACEHOLDER format (urn:edt:placeholder:duis, root DCCAlert)
 * until DCC gives samples; a real Response in the placeholder format, and a device alert, are unreadable.
 * The body comes from outside, so DTDs are refused (no XXE).
 */
public class XmlDspMessageReader implements DspMessageReader {

    static final String NAMESPACE = "urn:edt:placeholder:duis";
    static final String RESPONSE_NAMESPACE = "http://www.dccinterface.co.uk/ResponseAndAlert";

    @Override
    public DspMessage read(String body) {
        Document document = parse(body);
        Element root = document.getDocumentElement();
        if (RESPONSE_NAMESPACE.equals(root.getNamespaceURI()) && "GBCSResponse".equals(root.getLocalName())) {
            return readResponse(root, body);
        }
        if (!NAMESPACE.equals(root.getNamespaceURI())) {
            throw new UnreadableMessageException("unexpected namespace " + root.getNamespaceURI(), null);
        }
        if ("DCCAlert".equals(root.getLocalName())) {
            return new DspMessage(DspMessage.Kind.DCC_ALERT,
                    text(root, NAMESPACE, "RequestId"), text(root, NAMESPACE, "AlertCode"), body);
        }
        throw new UnreadableMessageException("not a Response or DCC Alert: " + root.getLocalName(), null);
    }

    /** Request ID = target:originator:counter of the header; through a Communications Hub, the supplementary fields. */
    private static DspMessage readResponse(Element root, String body) {
        if (root.getElementsByTagNameNS(RESPONSE_NAMESPACE, "ResponseMessage").getLength() == 0) {
            throw new UnreadableMessageException("a GBCSResponse without a ResponseMessage (a device alert?)", null);
        }
        String originator = text(root, RESPONSE_NAMESPACE, "BusinessOriginatorID");
        String requestId;
        if (root.getElementsByTagNameNS(RESPONSE_NAMESPACE, "SupplementaryRemotePartyID").getLength() > 0) {
            requestId = text(root, RESPONSE_NAMESPACE, "SupplementaryRemotePartyID") + ":" + originator + ":"
                    + text(root, RESPONSE_NAMESPACE, "SupplementaryRemotePartyCounter");
        } else {
            requestId = text(root, RESPONSE_NAMESPACE, "BusinessTargetID") + ":" + originator + ":"
                    + text(root, RESPONSE_NAMESPACE, "OriginatorCounter");
        }
        return new DspMessage(DspMessage.Kind.RESPONSE, requestId, null, body);
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new UnreadableMessageException("not well-formed XML: " + e.getMessage(), e);
        }
    }

    private static String text(Element root, String namespace, String name) {
        var nodes = root.getElementsByTagNameNS(namespace, name);
        String value = nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
        if (value.isEmpty()) {
            throw new UnreadableMessageException("missing " + name, null);
        }
        return value;
    }
}
