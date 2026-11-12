package prepare.edt.duissigner.adapter.kms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import prepare.edt.duissigner.adapter.JcaXmlSigner;
import prepare.edt.duissigner.entity.UserRole;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyUsageType;

/**
 * The EDT-KMS provider against a real KMS API: Floci, started by Testcontainers (needs Docker).
 *
 * Setup, like dcc-tools/kms-setup but for one role: a KMS key "alias/edt-other-user",
 * a test CA, a certificate for the KMS public key, and a certificate store (PKCS#12, certificates only).
 */
@Testcontainers
class KmsProviderTest {

    static final String ROLE_ALIAS = "other-user";
    static final char[] STORE_PASSWORD = "test-only".toCharArray();

    static final String UNSIGNED = """
            <ServiceRequest xmlns="urn:edt:placeholder:duis">\
            <Header><RequestId>REQ-1</RequestId><Originator>90-B3-D5-1F-30-01-00-01</Originator>\
            <SRV>4.1.1</SRV><DeviceType>ESME</DeviceType></Header>\
            <Body><ReadInstantaneousImportRegisters/></Body>\
            </ServiceRequest>""";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>("floci/floci:2.4.0")
            .withExposedPorts(4566)
            .waitingFor(Wait.forHealthcheck());   // the image's own healthcheck

    static KmsClient kms;
    static KeyStore kmsKeyStore;
    static X509Certificate roleCertificate;

    @BeforeAll
    static void setUp() throws Exception {
        kms = KmsClient.builder()
                .endpointOverride(URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566)))
                .region(Region.EU_WEST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClient(UrlConnectionHttpClient.create())
                .build();

        // The private key is created inside KMS. We only ever see its public key.
        String keyId = kms.createKey(r -> r.keySpec(KeySpec.ECC_NIST_P256).keyUsage(KeyUsageType.SIGN_VERIFY))
                .keyMetadata().keyId();
        kms.createAlias(r -> r.aliasName("alias/edt-" + ROLE_ALIAS).targetKeyId(keyId));
        PublicKey kmsPublicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(
                kms.getPublicKey(r -> r.keyId(keyId)).publicKey().asByteArray()));

        KeyPair ca = KeyPairGenerator.getInstance("EC").genKeyPair();
        X509Certificate caCertificate = certificate("CN=Test CA", ca.getPublic(), ca.getPrivate(), true);
        roleCertificate = certificate("CN=EDT Other User (TEST)", kmsPublicKey, ca.getPrivate(), false);

        Security.addProvider(new KmsProvider(kms, "alias/edt-"));

        // JCA: the KMS KeyStore of our provider, loaded from the certificate store.
        kmsKeyStore = KeyStore.getInstance("KMS", KmsProvider.NAME);
        kmsKeyStore.load(new ByteArrayInputStream(certificateStore(caCertificate, roleCertificate)), STORE_PASSWORD);
    }

    @AfterAll
    static void tearDown() {
        Security.removeProvider(KmsProvider.NAME);
        kms.close();
    }

    @Test
    void the_xml_signer_signs_through_kms_without_any_change() throws Exception {
        var signer = new JcaXmlSigner(kmsKeyStore, null, Map.of(UserRole.OTHER_USER, ROLE_ALIAS), KmsProvider.NAME);

        String signed = signer.sign(UNSIGNED, UserRole.OTHER_USER);

        assertTrue(verifyXml(signed, roleCertificate.getPublicKey()));
    }

    @Test
    void a_plain_jca_signature_made_by_kms_verifies_with_the_certificate() throws Exception {
        byte[] data = "anything".getBytes(StandardCharsets.UTF_8);

        Signature kmsSignature = Signature.getInstance("SHA256withECDSA", KmsProvider.NAME);
        kmsSignature.initSign((PrivateKey) kmsKeyStore.getKey(ROLE_ALIAS, null));
        kmsSignature.update(data);
        byte[] signature = kmsSignature.sign();

        Signature jdk = Signature.getInstance("SHA256withECDSA", "SunEC");
        jdk.initVerify(roleCertificate);
        jdk.update(data);
        assertTrue(jdk.verify(signature), "KMS must sign the digest exactly as given (MessageType=DIGEST)");
    }

    @Test
    void the_private_key_is_only_a_handle() throws Exception {
        Key key = kmsKeyStore.getKey(ROLE_ALIAS, null);

        KmsPrivateKey handle = assertInstanceOf(KmsPrivateKey.class, key);
        assertEquals("alias/edt-other-user", handle.kmsKeyId());
        assertNull(key.getEncoded(), "no key material leaves KMS");
    }

    @Test
    void the_ca_certificate_is_not_a_key_entry() throws Exception {
        assertTrue(kmsKeyStore.isKeyEntry(ROLE_ALIAS));
        assertFalse(kmsKeyStore.isKeyEntry("test-ca"));
        assertTrue(kmsKeyStore.isCertificateEntry("test-ca"));
        assertNull(kmsKeyStore.getKey("test-ca", null));
    }

    @Test
    void the_keystore_is_read_only() {
        assertThrows(UnsupportedOperationException.class, () -> kmsKeyStore.deleteEntry(ROLE_ALIAS));
    }

    // --- test helpers ---

    static X509Certificate certificate(String subject, PublicKey subjectKey, PrivateKey caKey, boolean isCa)
            throws Exception {
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(new X500Name("CN=Test CA"), BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(Duration.ofMinutes(5))), Date.from(now.plus(Duration.ofDays(1))),
                new X500Name(subject), subjectKey);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCa));
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(caKey)));
    }

    static byte[] certificateStore(X509Certificate ca, X509Certificate role) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("test-ca", ca);
        store.setCertificateEntry(ROLE_ALIAS, role);
        var out = new ByteArrayOutputStream();
        store.store(out, STORE_PASSWORD);
        return out.toByteArray();
    }

    static boolean verifyXml(String signedXml, PublicKey publicKey) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(new InputSource(new java.io.StringReader(signedXml)));
        var context = new DOMValidateContext(publicKey,
                document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature").item(0));
        return XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context).validate(context);
    }
}
