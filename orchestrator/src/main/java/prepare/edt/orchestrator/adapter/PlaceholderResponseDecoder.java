package prepare.edt.orchestrator.adapter;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import prepare.edt.orchestrator.entity.Facts;
import prepare.edt.orchestrator.usecase.ResponseDecoder;

/**
 * PLACEHOLDER for Parsec. Reads the simulator's placeholder Response:
 *
 *   <Response xmlns="urn:edt:placeholder:duis"> ... <Body><InstantaneousImportRegister>12345.6</...></Body></Response>
 *
 * and takes each simple element of the Body as a Fact (name -> text). The SRV is not used here; a real decoder
 * would use it to know which GBCS message to expect and which values to take.
 */
public class PlaceholderResponseDecoder implements ResponseDecoder {

    private static final String NAMESPACE = "urn:edt:placeholder:duis";

    @Override
    public Facts decode(String srv, String responseXml) {
        Document document = parse(responseXml);
        Element root = document.getDocumentElement();
        if (!NAMESPACE.equals(root.getNamespaceURI()) || !"Response".equals(root.getLocalName())) {
            throw new UndecodableResponseException("not a Response", null);
        }
        var bodies = root.getElementsByTagNameNS(NAMESPACE, "Body");
        if (bodies.getLength() != 1) {
            throw new UndecodableResponseException("the Response has no Body", null);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Node child = bodies.item(0).getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && !hasChildElements(element)) {
                values.put(element.getLocalName(), element.getTextContent().trim());
            }
        }
        return Facts.of(values);
    }

    private static boolean hasChildElements(Element element) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) {
                return true;
            }
        }
        return false;
    }

    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new UndecodableResponseException("not well-formed XML: " + e.getMessage(), e);
        }
    }
}
