package prepare.edt.batchingest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import prepare.edt.batchingest.entity.ManifestRef;
import prepare.edt.batchingest.usecase.ManifestVerifier.Verdict;

/** The verifier with the software provider SunEC: the same JCA code the application runs with EDT-KMS. */
class S3ManifestVerifierTest {

    static final String TAG = "dcc-signature";
    static final ManifestRef MANIFEST = new ManifestRef("edt-manifests", "sample.csv", "v2");
    static final byte[] CSV = "device_id,device_type,flow\n00-DB-12-34-56-78-90-A0,ESME,flow-1\n"
            .getBytes(StandardCharsets.UTF_8);

    final KeyPair dcc = keyPair();

    Verdict verify(FakeS3 s3) {
        return new S3ManifestVerifier(s3, TAG, "SunEC", dcc.getPublic()).verify(MANIFEST);
    }

    FakeS3 uploaded(byte[] content, String signatureTag) {
        FakeS3 s3 = new FakeS3("v2", content);
        if (signatureTag != null) {
            s3.tags.put(TAG, signatureTag);
        }
        s3.tags.put("other", "x");
        return s3;
    }

    @Test
    void a_manifest_signed_with_dccs_key_is_valid() throws Exception {
        assertEquals(Verdict.signedByDcc(), verify(uploaded(CSV, sign(CSV))));
    }

    @Test
    void a_manifest_changed_after_signing_is_invalid() throws Exception {
        byte[] changed = new String(CSV, StandardCharsets.UTF_8).replace("A0", "A9").getBytes(StandardCharsets.UTF_8);

        assertEquals(Verdict.invalid("signature does not match the Manifest"), verify(uploaded(changed, sign(CSV))));
    }

    @Test
    void a_manifest_without_the_tag_is_invalid() {
        assertEquals(Verdict.invalid("no dcc-signature tag"), verify(uploaded(CSV, null)));
    }

    @Test
    void a_tag_that_is_not_base64_is_invalid() {
        assertEquals(Verdict.invalid("the dcc-signature tag is not base64"), verify(uploaded(CSV, "not base64!")));
    }

    @Test
    void a_tag_that_is_not_a_signature_is_invalid() {
        String garbage = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.US_ASCII));

        assertEquals(Verdict.invalid("signature is malformed"), verify(uploaded(CSV, garbage)));
    }

    /** What DCC does (manifest-upload in dcc-tools does it with KMS): ECDSA over the SHA-256 of the file. */
    String sign(byte[] content) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(dcc.getPrivate());
        signature.update(content);
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
