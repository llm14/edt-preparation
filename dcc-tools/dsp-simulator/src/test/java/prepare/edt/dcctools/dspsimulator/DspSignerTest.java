package prepare.edt.dcctools.dspsimulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/** The simulator's messages are signed so that the DSP certificate verifies them, and tampering breaks that. */
class DspSignerTest {

    static DspSigner signer;
    static X509Certificate certificate;

    @BeforeAll
    static void keys(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("dsp.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process process = new ProcessBuilder(List.of(keytool.toString(), "-genkeypair", "-alias", "dsp",
                "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                "-dname", "CN=DSP Signing (TEST)", "-validity", "30", "-storetype", "PKCS12",
                "-keystore", file.toString(), "-storepass", "test-only", "-keypass", "test-only"))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);

        signer = DspSigner.load(file, "test-only".toCharArray(), "dsp");
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (var in = java.nio.file.Files.newInputStream(file)) {
            keystore.load(in, "test-only".toCharArray());
        }
        certificate = (X509Certificate) keystore.getCertificate("dsp");
    }

    @Test
    void a_signed_response_verifies_with_the_dsp_certificate() throws Exception {
        assertTrue(verifies(signer.sign(DspMessages.response("R-1"))));
        assertTrue(verifies(signer.sign(DspMessages.dccAlert("R-1", "N12"))));
        assertTrue(verifies(signer.sign(DspMessages.syncResponse("R-1", true, "accepted"))));
    }

    @Test
    void a_tampered_response_does_not() throws Exception {
        assertFalse(verifies(DspMessages.tamper(signer.sign(DspMessages.response("R-1")))));
        assertFalse(verifies(DspMessages.tamper(signer.sign(DspMessages.syncResponse("R-1", true, "accepted")))));
    }

    @Test
    void A6_and_A7_tamper() {
        assertTrue(DeviceBehaviour.forTarget("X-A6").tamperAsync);
        assertTrue(DeviceBehaviour.forTarget("X-A7").tamperSync);
        assertFalse(DeviceBehaviour.forTarget("X-A0").tamperAsync);
    }

    static boolean verifies(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        var signature = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0);
        var context = new DOMValidateContext(certificate.getPublicKey(), signature);
        return XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context).validate(context);
    }
}
