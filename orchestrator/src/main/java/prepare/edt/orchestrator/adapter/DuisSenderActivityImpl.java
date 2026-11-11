package prepare.edt.orchestrator.adapter;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Posts the signed Service Request once to the DSP HTTPS Gateway (plain HTTP, the gateway does the DCCKI TLS)
 * and reads the synchronous DUIS response.
 *
 * An HTTP error or a network failure throws: with one attempt, the workflow records SEND_FAILED.
 * PLACEHOLDER format: response codes starting with "I" mean accepted (I0), others rejected.
 * Not yet done (later step): checking the DSP signature of the synchronous response, as the model says.
 */
public final class DuisSenderActivityImpl implements DuisSenderActivity {

    private static final String NAMESPACE = "urn:edt:placeholder:duis";

    private final URI gateway;
    private final HttpClient http;

    public DuisSenderActivityImpl(URI gateway) {
        this.gateway = gateway;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public SyncResponse send(String signedServiceRequest) {
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(gateway)
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/xml")
                            .POST(HttpRequest.BodyPublishers.ofString(signedServiceRequest))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while posting to " + gateway, e);
        } catch (Exception e) {
            throw new IllegalStateException("cannot post to " + gateway + ": " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("DSP HTTPS Gateway answered HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        return read(response.body());
    }

    static SyncResponse read(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            String code = text(document, "ResponseCode");
            return new SyncResponse(code.startsWith("I"), code, text(document, "Detail"));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the synchronous DUIS response: " + e.getMessage(), e);
        }
    }

    private static String text(Document document, String name) {
        var nodes = document.getElementsByTagNameNS(NAMESPACE, name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
}
