package prepare.edt.dcctools.dspsimulator;

import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Signs the DSP's messages (synchronous response, Response, DCC Alert) with the DSP's key, the way the EDT signs
 * Service Requests: enveloped XML signature, ECDSA with SHA-256, exclusive canonicalisation, certificate in KeyInfo.
 *
 * The key comes from the DSP keystore that kms-setup writes (dcc-tools/dsp-keys), which only this simulator mounts.
 * TEST key only. The real DSP's signature format is set by DUIS, not by this project.
 */
final class DspSigner {

    private final PrivateKey key;
    private final X509Certificate certificate;
    private final XMLSignatureFactory xmlSignatures = XMLSignatureFactory.getInstance("DOM");

    DspSigner(PrivateKey key, X509Certificate certificate) {
        this.key = key;
        this.certificate = certificate;
    }

    /** Loads the key and certificate under the alias from a PKCS#12 keystore. */
    static DspSigner load(Path keystoreFile, char[] password, String alias) throws Exception {
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystoreFile)) {
            keystore.load(in, password);
        }
        PrivateKey key = (PrivateKey) keystore.getKey(alias, password);
        X509Certificate certificate = (X509Certificate) keystore.getCertificate(alias);
        if (key == null || certificate == null) {
            throw new IllegalStateException("no key or certificate for alias " + alias + " in " + keystoreFile);
        }
        return new DspSigner(key, certificate);
    }

    String sign(String xml) {
        try {
            Document document = parse(xml);
            Reference reference = xmlSignatures.newReference("",
                    xmlSignatures.newDigestMethod(DigestMethod.SHA256, null),
                    List.of(xmlSignatures.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
                    null, null);
            SignedInfo signedInfo = xmlSignatures.newSignedInfo(
                    xmlSignatures.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                            (C14NMethodParameterSpec) null),
                    xmlSignatures.newSignatureMethod(SignatureMethod.ECDSA_SHA256, null),
                    List.of(reference));
            KeyInfoFactory keyInfos = xmlSignatures.getKeyInfoFactory();
            var keyInfo = keyInfos.newKeyInfo(List.of(keyInfos.newX509Data(List.of(certificate))));

            xmlSignatures.newXMLSignature(signedInfo, keyInfo)
                    .sign(new DOMSignContext(key, document.getDocumentElement()));
            return write(document);
        } catch (Exception e) {
            throw new IllegalStateException("DSP signing failed: " + e.getMessage(), e);
        }
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static String write(Document document) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(out));
        return out.toString();
    }
}
