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
 * Reads a Response or DCC Alert in the PLACEHOLDER format (urn:edt:placeholder:duis), not real DUIS.
 *
 * The root element says what it is: Response or DCCAlert. Both carry the RequestId; a DCC Alert also an AlertCode.
 * The body comes from outside, so DTDs are refused (no XXE).
 */
public class XmlDspMessageReader implements DspMessageReader {

    static final String NAMESPACE = "urn:edt:placeholder:duis";

    @Override
    public DspMessage read(String body) {
        Document document = parse(body);
        Element root = document.getDocumentElement();
        if (!NAMESPACE.equals(root.getNamespaceURI())) {
            throw new UnreadableMessageException("unexpected namespace " + root.getNamespaceURI(), null);
        }
        return switch (root.getLocalName()) {
            case "Response" -> new DspMessage(DspMessage.Kind.RESPONSE, text(root, "RequestId"), null, body);
            case "DCCAlert" -> new DspMessage(DspMessage.Kind.DCC_ALERT,
                    text(root, "RequestId"), text(root, "AlertCode"), body);
            default -> throw new UnreadableMessageException("not a Response or DCC Alert: " + root.getLocalName(), null);
        };
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

    private static String text(Element root, String name) {
        var nodes = root.getElementsByTagNameNS(NAMESPACE, name);
        String value = nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
        if (value.isEmpty()) {
            throw new UnreadableMessageException("missing " + name, null);
        }
        return value;
    }
}
