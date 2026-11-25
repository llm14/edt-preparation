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
 * The request is a DUIS 5.5 request. It names no originator and no device type: the originator is the first part of its
 * RequestID ("originator:device:counter"), and the device type is given by the caller (the Orchestrator knows it from the Manifest).
 *
 * Uses a PARTIAL schema (schema/service-request.xsd), written from the released examples, until DCC provides the DUIS schema.
 */
public final class XmlServiceRequestReader implements ServiceRequestReader {

    static final String NAMESPACE = "http://www.dccinterface.co.uk/ServiceUserGateway";
    private static final String SCHEMA = "/schema/service-request.xsd";

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
    public ServiceRequest read(String xml, String deviceType) {
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
        String requestId = text(document, "RequestID");
        // The schema guarantees "originator:device:counter", so there is a colon.
        String originator = requestId.substring(0, requestId.indexOf(':'));
        return new ServiceRequest(requestId, text(document, "ServiceReferenceVariant"), originator, deviceType, xml);
    }

    private static String text(Document document, String element) {
        return document.getElementsByTagNameNS(NAMESPACE, element).item(0).getTextContent().trim();
    }
}
