package prepare.edt.duissigner.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.Security;
import java.util.Map;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import prepare.edt.duissigner.entity.UserRole;

/** Signs with real (test) keys through JCA, then verifies the XML signature. */
class JcaXmlSignerTest {

    static final String UNSIGNED = """
            <ServiceRequest xmlns="urn:edt:placeholder:duis">\
            <Header><RequestId>REQ-1</RequestId><Originator>90-B3-D5-1F-30-01-00-01</Originator>\
            <SRV>4.1.1</SRV><DeviceType>ESME</DeviceType></Header>\
            <Body><ReadInstantaneousImportRegisters/></Body>\
            </ServiceRequest>""";

    static final Map<UserRole, String> ALIASES = Map.of(
            UserRole.OTHER_USER, TestKeys.OTHER_USER,
            UserRole.REGISTERED_SUPPLIER_AGENT, TestKeys.REGISTERED_SUPPLIER_AGENT);

    static KeyStore keyStore;

    @BeforeAll
    static void createKeys(@TempDir Path directory) throws Exception {
        keyStore = TestKeys.create(directory);
        Security.addProvider(new SpyProvider());
    }

    @AfterAll
    static void removeSpy() {
        Security.removeProvider(SpyProvider.NAME);
    }

    final JcaXmlSigner signer = new JcaXmlSigner(keyStore, TestKeys.PASSWORD, ALIASES, "SunEC");

    @Test
    void signature_is_valid_with_the_roles_certificate() throws Exception {
        String signed = signer.sign(UNSIGNED, UserRole.OTHER_USER);

        assertTrue(verify(signed, publicKeyOf(TestKeys.OTHER_USER)));
        assertTrue(signed.contains("http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256"));
    }

    @Test
    void signature_is_not_valid_with_the_other_roles_certificate() throws Exception {
        String signed = signer.sign(UNSIGNED, UserRole.OTHER_USER);

        assertFalse(verify(signed, publicKeyOf(TestKeys.REGISTERED_SUPPLIER_AGENT)));
    }

    @Test
    void a_changed_request_no_longer_verifies() throws Exception {
        String signed = signer.sign(UNSIGNED, UserRole.OTHER_USER);
        String tampered = signed.replace("<SRV>4.1.1</SRV>", "<SRV>4.1.2</SRV>");

        assertFalse(verify(tampered, publicKeyOf(TestKeys.OTHER_USER)));
    }

    @Test
    void the_configured_provider_does_the_signing() throws Exception {
        var spySigner = new JcaXmlSigner(keyStore, TestKeys.PASSWORD, ALIASES, SpyProvider.NAME);
        int before = SpyProvider.SIGNATURES.get();

        String signed = spySigner.sign(UNSIGNED, UserRole.REGISTERED_SUPPLIER_AGENT);

        assertEquals(before + 1, SpyProvider.SIGNATURES.get());
        assertTrue(verify(signed, publicKeyOf(TestKeys.REGISTERED_SUPPLIER_AGENT)));
    }

    @Test
    void an_unknown_provider_fails_at_startup() {
        assertThrows(IllegalArgumentException.class,
                () -> new JcaXmlSigner(keyStore, TestKeys.PASSWORD, ALIASES, "NO-SUCH-PROVIDER"));
    }

    static PublicKey publicKeyOf(String alias) throws Exception {
        return keyStore.getCertificate(alias).getPublicKey();
    }

    /** Verifies the enveloped XML signature with the given public key (what a receiver would do). */
    static boolean verify(String signedXml, PublicKey publicKey) throws Exception {
        Document document = SecureXml.parse(signedXml);
        NodeList signatures = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        assertEquals(1, signatures.getLength(), "exactly one signature expected");

        DOMValidateContext context = new DOMValidateContext(publicKey, signatures.item(0));
        XMLSignature signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context);
        return signature.validate(context);
    }
}
