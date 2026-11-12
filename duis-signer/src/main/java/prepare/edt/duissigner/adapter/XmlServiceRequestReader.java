package prepare.edt.duissigner.adapter;

import java.net.URL;
import javax.xml.XMLConstants;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.w3c.dom.Document;
import prepare.edt.duissigner.entity.ServiceRequest;
import prepare.edt.duissigner.usecase.ServiceRequestReader;

/**
 * Reads an unsigned Service Request: parses it safely, validates it against the schema,
 * and takes the values the signing policy needs.
 *
 * Uses the PLACEHOLDER schema (schema/service-request-placeholder.xsd) until DCC provides the DUIS schema.
 */
public final class XmlServiceRequestReader implements ServiceRequestReader {

    static final String NAMESPACE = "urn:edt:placeholder:duis";
    private static final String SCHEMA = "/schema/service-request-placeholder.xsd";

    private final Schema schema;

    public XmlServiceRequestReader() {
        try {
            URL xsd = XmlServiceRequestReader.class.getResource(SCHEMA);
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            this.schema = factory.newSchema(xsd);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load schema " + SCHEMA, e);
        }
    }

    @Override
    public ServiceRequest read(String xml) {
        Document document;
        try {
            document = SecureXml.parse(xml);
            var validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.validate(new DOMSource(document));
        } catch (Exception e) {
            throw new InvalidServiceRequestException(e.getMessage());
        }
        return new ServiceRequest(
                text(document, "RequestId"),
                text(document, "SRV"),
                text(document, "Originator"),
                text(document, "DeviceType"),
                xml);
    }

    private static String text(Document document, String element) {
        return document.getElementsByTagNameNS(NAMESPACE, element).item(0).getTextContent().trim();
    }
}
