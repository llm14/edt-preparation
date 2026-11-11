package prepare.edt.orchestrator.adapter;

import java.io.StringReader;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Checks the DSP's XML signature on a message, with the trusted DSP certificate from the certificate store
 * (the model's "DUIS Library": "checks the DSP XML signature with the trusted DSP certificate").
 *
 * Only the trusted certificate's key is used. The certificate inside the message (KeyInfo) is ignored:
 * anyone can put a certificate there.
 *
 * The rules, all required:
 *   - exactly one ds:Signature, a direct child of the root element;
 *   - one Reference, with URI "" (the whole document) and the enveloped-signature transform,
 *     so the signature covers everything the EDT will read;
 *   - ECDSA with SHA-256;
 *   - the signature value and the digest are valid for the trusted key.
 * The JDK's secure validation mode is on: it refuses weak algorithms and some denial-of-service tricks.
 */
public final class DspSignatureVerifier {

    /** The outcome of a check: valid, or the reason it is not. */
    public record Verdict(boolean valid, String reason) {
        static Verdict ok() {
            return new Verdict(true, null);
        }

        static Verdict invalid(String reason) {
            return new Verdict(false, reason);
        }
    }

    private final PublicKey trustedKey;
    private final XMLSignatureFactory xmlSignatures = XMLSignatureFactory.getInstance("DOM");

    public DspSignatureVerifier(X509Certificate trustedDspCertificate) {
        this.trustedKey = trustedDspCertificate.getPublicKey();
    }

    public Verdict check(String xml) {
        try {
            Document document = parse(xml);
            NodeList signatures = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
            if (signatures.getLength() != 1) {
                return Verdict.invalid(signatures.getLength() == 0 ? "not signed" : "more than one signature");
            }
            if (signatures.item(0).getParentNode() != document.getDocumentElement()) {
                return Verdict.invalid("signature is not a child of the root element");
            }

            DOMValidateContext context = new DOMValidateContext(trustedKey, signatures.item(0));
            context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
            XMLSignature signature = xmlSignatures.unmarshalXMLSignature(context);

            var signedInfo = signature.getSignedInfo();
            if (!SignatureMethod.ECDSA_SHA256.equals(signedInfo.getSignatureMethod().getAlgorithm())) {
                return Verdict.invalid("unexpected signature algorithm " + signedInfo.getSignatureMethod().getAlgorithm());
            }
            if (signedInfo.getReferences().size() != 1) {
                return Verdict.invalid("expected one reference");
            }
            Reference reference = (Reference) signedInfo.getReferences().get(0);
            boolean enveloped = reference.getTransforms().stream()
                    .anyMatch(t -> Transform.ENVELOPED.equals(((Transform) t).getAlgorithm()));
            if (!"".equals(reference.getURI()) || !enveloped) {
                return Verdict.invalid("the signature does not cover the whole document");
            }

            return signature.validate(context) ? Verdict.ok() : Verdict.invalid("signature does not match");
        } catch (Exception e) {
            return Verdict.invalid("cannot check the signature: " + e.getMessage());
        }
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }
}
