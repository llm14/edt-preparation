package prepare.edt.dcctools.kmssetup;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.kms.model.NotFoundException;

/**
 * Plays DCC's part for local development: creates the User Role keys in KMS (Floci) and the certificate store.
 *
 *   1. For each User Role, a KMS key (ECC_NIST_P256, SIGN_VERIFY) with alias "alias/edt-<role>".
 *      The private key is created inside KMS and never leaves it.
 *   2. A test CA (a software key pair, in memory only).
 *   3. For each role, a certificate holding the KMS public key, signed by the test CA.
 *   4. The DSP's signing key (the DSP is outside the EDT and not in KMS: a software key pair) and its certificate.
 *      The private key goes into a SEPARATE keystore that only the DSP simulator mounts.
 *   5. The certificate store: a PKCS#12 file with the certificates only (no private keys): the User Roles
 *      (for the DUIS Signer) and the trusted DSP signing certificate (for the Orchestrator and the Response Listener).
 *      The EDT containers mount it read-only.
 *
 * Floci keeps everything in memory, so this runs at every start of dcc-tools. TEST keys and certificates only.
 *
 * Configuration (environment variables):
 *   EDT_KMS_ENDPOINT        KMS endpoint, for example http://floci:4566
 *   AWS_REGION              for example eu-west-2
 *   AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY   any non-empty value: Floci does not check them
 *   EDT_CERT_STORE          output file, for example /certs/certificate-store.p12
 *   EDT_CERT_STORE_PASSWORD password of the certificate store
 *   EDT_DSP_KEYSTORE        output file for the DSP's private key, for example /dsp-keys/dsp-signing.p12
 *   EDT_DSP_KEYSTORE_PASSWORD password of that keystore
 */
public final class KmsSetup {

    /** Certificate store alias (also the role name) and certificate subject, per User Role. */
    static final Map<String, String> ROLES = Map.of(
            "other-user", "CN=EDT Other User (TEST)",
            "registered-supplier-agent", "CN=EDT Registered Supplier Agent (TEST)");

    static final String CA_ALIAS = "edt-test-ca";
    static final String DSP_ALIAS = "dsp";
    static final String DSP_SUBJECT = "CN=DSP Signing (TEST)";
    static final Duration VALIDITY = Duration.ofDays(30);

    public static void main(String[] args) throws Exception {
        Path storeFile = Path.of(env("EDT_CERT_STORE"));
        char[] storePassword = env("EDT_CERT_STORE_PASSWORD").toCharArray();

        try (KmsClient kms = KmsClient.builder()
                .endpointOverride(URI.create(env("EDT_KMS_ENDPOINT")))
                .region(Region.of(env("AWS_REGION")))
                .build()) {

            KeyPair ca = generateEcKeyPair();
            X509Certificate caCertificate = certificate("CN=EDT Test CA (TEST)", ca.getPublic(), "CN=EDT Test CA (TEST)",
                    ca.getPrivate(), true);

            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setCertificateEntry(CA_ALIAS, caCertificate);

            for (var role : ROLES.entrySet()) {
                String kmsAlias = "alias/edt-" + role.getKey();
                String keyId = findOrCreateKey(kms, kmsAlias);
                PublicKey kmsPublicKey = publicKey(kms, keyId);
                X509Certificate roleCertificate = certificate(role.getValue(), kmsPublicKey,
                        "CN=EDT Test CA (TEST)", ca.getPrivate(), false);
                store.setCertificateEntry(role.getKey(), roleCertificate);
                System.out.printf("role=%s kms_alias=%s kms_key_id=%s certificate=\"%s\"%n",
                        role.getKey(), kmsAlias, keyId, role.getValue());
            }

            // The DSP: a software key pair, certified by the same test CA.
            KeyPair dsp = generateEcKeyPair();
            X509Certificate dspCertificate = certificate(DSP_SUBJECT, dsp.getPublic(), "CN=EDT Test CA (TEST)",
                    ca.getPrivate(), false);
            store.setCertificateEntry(DSP_ALIAS, dspCertificate);   // only the certificate goes to the EDT

            Path dspKeystoreFile = Path.of(env("EDT_DSP_KEYSTORE"));
            char[] dspPassword = env("EDT_DSP_KEYSTORE_PASSWORD").toCharArray();
            KeyStore dspKeystore = KeyStore.getInstance("PKCS12");
            dspKeystore.load(null, null);
            dspKeystore.setKeyEntry(DSP_ALIAS, dsp.getPrivate(), dspPassword,
                    new X509Certificate[] {dspCertificate, caCertificate});
            write(dspKeystore, dspKeystoreFile, dspPassword);
            System.out.printf("dsp_keystore=%s certificate=\"%s\"%n", dspKeystoreFile, DSP_SUBJECT);

            write(store, storeFile, storePassword);
            System.out.printf("certificate_store=%s entries=%d%n", storeFile, store.size());
        }
    }

    /** Reuses the key behind the alias if it exists (setup run twice), otherwise creates a new one. */
    static String findOrCreateKey(KmsClient kms, String alias) {
        try {
            return kms.describeKey(r -> r.keyId(alias)).keyMetadata().keyId();
        } catch (NotFoundException e) {
            String keyId = kms.createKey(r -> r
                            .keySpec(KeySpec.ECC_NIST_P256)
                            .keyUsage(KeyUsageType.SIGN_VERIFY)
                            .description("EDT test key " + alias))
                    .keyMetadata().keyId();
            kms.createAlias(r -> r.aliasName(alias).targetKeyId(keyId));
            return keyId;
        }
    }

    /** KMS returns the public key as DER (SubjectPublicKeyInfo); JCA's KeyFactory turns it into a PublicKey. */
    static PublicKey publicKey(KmsClient kms, String keyId) throws Exception {
        byte[] der = kms.getPublicKey(r -> r.keyId(keyId)).publicKey().asByteArray();
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
    }

    static KeyPair generateEcKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    /**
     * Builds an X.509 certificate for subjectKey, signed by issuerKey.
     * The subject's private key is not needed: that is how a CA can certify a key that lives in KMS.
     */
    static X509Certificate certificate(String subject, PublicKey subjectKey, String issuer, PrivateKey issuerKey,
                                       boolean isCa) throws Exception {
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(
                new X500Name(issuer),
                new BigInteger(64, new SecureRandom()),
                Date.from(now.minus(Duration.ofMinutes(5))),
                Date.from(now.plus(VALIDITY)),
                new X500Name(subject),
                subjectKey);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCa));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(
                isCa ? KeyUsage.keyCertSign : KeyUsage.digitalSignature));

        // The CA signs through JCA: JcaContentSignerBuilder uses Signature.getInstance("SHA256withECDSA").
        var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey);
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    /** Writes to a temporary file first, then renames it, so a reader never sees a half-written store. */
    static void write(KeyStore store, Path file, char[] password) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temporary)) {
            store.store(out, password);
        }
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing environment variable " + name);
        }
        return value;
    }
}
