package prepare.edt.batchingest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

    @Test
    void a_valid_manifest_writes_a_read_line_and_a_signature_line() throws Exception {
        String signature = sign(CSV);
        try (LogCapture log = new LogCapture()) {
            verify(uploaded(CSV, signature));

            var lines = log.lines("edt.interaction");
            assertEquals(List.of("manifest-read", "manifest-signature-checked"),
                    lines.stream().map(l -> l.fields().get("event.action")).toList());
            var read = lines.get(0).fields();
            assertEquals("edt.batchIngestReqS3", read.get("edt.interface"));
            assertEquals(String.valueOf(CSV.length), read.get("edt.manifest.bytes_read"));
            assertEquals("edt-manifests", read.get("edt.manifest.bucket"));
            var check = lines.get(1).fields();
            assertEquals("edt.batchIngestReqKms", check.get("edt.interface"));
            assertEquals("SunEC", check.get("edt.signature_provider"));
            for (var line : lines) {
                assertEquals("success", line.fields().get("event.outcome"));
                assertEquals("outbound", line.fields().get("network.direction"));
                assertTrue(Long.parseLong(line.fields().get("event.duration")) >= 0);
            }
            assertNoSecrets(lines, signature);
        }
    }

    @Test
    void an_invalid_manifest_still_writes_both_lines_and_the_signature_line_is_a_failure() throws Exception {
        byte[] changed = new String(CSV, StandardCharsets.UTF_8).replace("A0", "A9").getBytes(StandardCharsets.UTF_8);
        String signature = sign(CSV);
        try (LogCapture log = new LogCapture()) {
            verify(uploaded(changed, signature));

            var lines = log.lines("edt.interaction");
            assertEquals(List.of("manifest-read", "manifest-signature-checked"),
                    lines.stream().map(l -> l.fields().get("event.action")).toList());
            assertEquals("success", lines.get(0).fields().get("event.outcome"));
            assertEquals("failure", lines.get(1).fields().get("event.outcome"));
            assertNoSecrets(lines, signature);
        }
    }

    @Test
    void a_manifest_without_a_signature_tag_is_a_failure_on_the_signature_line() {
        try (LogCapture log = new LogCapture()) {
            verify(uploaded(CSV, null));

            var last = log.lines("edt.interaction").getLast();
            assertEquals("manifest-signature-checked", last.fields().get("event.action"));
            assertEquals("failure", last.fields().get("event.outcome"));
        }
    }

    /** Neither the signature value nor any part of the Manifest is in a line (message or field). */
    static void assertNoSecrets(List<LogCapture.Line> lines, String signature) {
        for (var line : lines) {
            var all = new ArrayList<>(line.fields().values());
            all.add(line.message());
            for (String value : all) {
                assertFalse(value.contains(signature), "the signature is in " + line);
                assertFalse(value.contains("00-DB-12-34"), "Manifest content is in " + line);
            }
        }
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
