package prepare.edt.orchestrator.adapter;

import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
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
 * A test DSP: a key pair and certificate made with the JDK's keytool, and a signer that signs the way the DSP
 * simulator does (enveloped, ECDSA-SHA256, exclusive C14N). TEST keys only.
 */
final class TestDsp {

    final PrivateKey key;
    final X509Certificate certificate;

    private TestDsp(PrivateKey key, X509Certificate certificate) {
        this.key = key;
        this.certificate = certificate;
    }

    static TestDsp create(Path directory, String name) throws Exception {
        Path file = directory.resolve(name + ".p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process process = new ProcessBuilder(List.of(keytool.toString(), "-genkeypair", "-alias", "dsp",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-dname", "CN=" + name + " (TEST)", "-validity", "30", "-storetype", "PKCS12",
                "-keystore", file.toString(), "-storepass", "test-only", "-keypass", "test-only"))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, "test-only".toCharArray());
        }
        return new TestDsp((PrivateKey) store.getKey("dsp", "test-only".toCharArray()),
                (X509Certificate) store.getCertificate("dsp"));
    }

    /** Signs with a Reference to the whole document (URI ""), as the DSP does. */
    String sign(String xml) throws Exception {
        return sign(xml, "");
    }

    String sign(String xml, String referenceUri) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        // A reference like "#p" points at an element by its Id attribute, which must be declared as an ID.
        var elements = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < elements.getLength(); i++) {
            var element = (org.w3c.dom.Element) elements.item(i);
            if (element.hasAttribute("Id")) {
                element.setIdAttribute("Id", true);
            }
        }
        XMLSignatureFactory signatures = XMLSignatureFactory.getInstance("DOM");
        var reference = signatures.newReference(referenceUri,
                signatures.newDigestMethod(DigestMethod.SHA256, null),
                List.of(signatures.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)), null, null);
        var signedInfo = signatures.newSignedInfo(
                signatures.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
                signatures.newSignatureMethod(SignatureMethod.ECDSA_SHA256, null), List.of(reference));
        var keyInfos = signatures.getKeyInfoFactory();
        signatures.newXMLSignature(signedInfo, keyInfos.newKeyInfo(List.of(keyInfos.newX509Data(List.of(certificate)))))
                .sign(new DOMSignContext(key, document.getDocumentElement()));

        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(out));
        return out.toString();
    }
}
