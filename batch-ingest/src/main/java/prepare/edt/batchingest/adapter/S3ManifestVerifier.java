package prepare.edt.batchingest.adapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Base64;
import java.util.Optional;
import prepare.edt.batchingest.entity.ManifestRef;
import prepare.edt.batchingest.infrastructure.InterfaceLog;
import prepare.edt.batchingest.infrastructure.LogFields;
import prepare.edt.batchingest.usecase.ManifestVerifier;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Tag;

/**
 * The Manifest Signature Verifier (model 100.010): "streams the Manifest from S3 to compute its SHA-256 digest and
 * checks the signature carried with the object with the AWS KMS Verify operation on the DCC Manifest signing key".
 *
 *   1. S3 GetObjectTagging of that version: the signature tag (base64 DER ECDSA)   [CONFIRM format with DCC]
 *   2. S3 GetObject of that version, read in chunks into a JCA Signature (never held whole in memory)
 *   3. Signature.verify
 *
 * Plain JCA: which provider does the work is configuration. In the application it is our EDT-KMS (KMS Verify, with a
 * KmsPublicKey handle); in unit tests SunEC with a software key.
 */
public final class S3ManifestVerifier implements ManifestVerifier {

    private final S3Client s3;
    private final String signatureTag;
    private final String signatureProvider;
    private final PublicKey dccManifestKey;

    public S3ManifestVerifier(S3Client s3, String signatureTag, String signatureProvider, PublicKey dccManifestKey) {
        this.s3 = s3;
        this.signatureTag = signatureTag;
        this.signatureProvider = signatureProvider;
        this.dccManifestKey = dccManifestKey;
    }

    /** Model interfaces: Manifest Signature Verifier to the Manifest Bucket (S3) and to AWS KMS. */
    static final String S3_INTERFACE = "edt.batchIngestReqS3";
    static final String KMS_INTERFACE = "edt.batchIngestReqKms";

    @Override
    public Verdict verify(ManifestRef manifest) {
        try (var ids = LogFields.of("edt.manifest.bucket", manifest.bucket(), "edt.manifest.key", manifest.key(),
                "edt.manifest.version_id", manifest.versionId())) {
            Signature signature = newVerifier();
            byte[] signatureBytes = null;
            Verdict problem = null;
            long bytesRead = 0;

            // Row 3: the S3 reads (the signature tag, then the object).
            try (var read = InterfaceLog.outbound(S3_INTERFACE, "manifest-read")) {
                try {
                    Optional<String> tag = s3.getObjectTagging(r -> r
                                    .bucket(manifest.bucket()).key(manifest.key()).versionId(manifest.versionId()))
                            .tagSet().stream()
                            .filter(t -> signatureTag.equals(t.key()))
                            .map(Tag::value)
                            .findFirst();
                    if (tag.isEmpty()) {
                        problem = Verdict.invalid("no " + signatureTag + " tag");
                    } else {
                        try {
                            signatureBytes = Base64.getDecoder().decode(tag.get());
                        } catch (IllegalArgumentException e) {
                            problem = Verdict.invalid("the " + signatureTag + " tag is not base64");
                        }
                    }
                    if (problem == null) {
                        try (InputStream in = s3.getObject(r -> r
                                .bucket(manifest.bucket()).key(manifest.key()).versionId(manifest.versionId()))) {
                            byte[] buffer = new byte[64 * 1024];
                            for (int n; (n = in.read(buffer)) != -1; ) {
                                signature.update(buffer, 0, n);
                                bytesRead += n;
                            }
                        }
                    }
                } catch (IOException e) {
                    read.failure().with("edt.manifest.bytes_read", bytesRead).log("Could not read the Manifest from the Manifest Bucket");
                    throw new UncheckedIOException("cannot read the Manifest from S3", e);
                } catch (SignatureException e) {
                    // update() only fails when the verifier is not initialised: a programming error, not bad input.
                    read.failure().with("edt.manifest.bytes_read", bytesRead).log("Could not read the Manifest from the Manifest Bucket");
                    throw new IllegalStateException("the Manifest verifier is not ready", e);
                } catch (RuntimeException e) {
                    read.failure().with("edt.manifest.bytes_read", bytesRead).log("Could not read the Manifest from the Manifest Bucket");
                    throw e;
                }
                read.with("edt.manifest.bytes_read", bytesRead).log("Manifest read from the Manifest Bucket");
            }

            // Row 4: the signature check, which is where the digest goes to KMS. No signature to check is a failure too.
            try (var check = InterfaceLog.outbound(KMS_INTERFACE, "manifest-signature-checked")
                    .with("edt.signature_provider", signatureProvider)) {
                if (problem != null) {
                    check.failure().log("Manifest signature not checked: " + problem.reason());
                    return problem;
                }
                Verdict verdict;
                try {
                    verdict = signature.verify(signatureBytes)
                            ? Verdict.signedByDcc()
                            : Verdict.invalid("signature does not match the Manifest");
                } catch (SignatureException e) {
                    // SunEC: the tag is not a valid DER ECDSA signature.
                    verdict = Verdict.invalid("signature is malformed");
                } catch (RuntimeException e) {
                    check.failure().log("Could not check the Manifest signature");
                    throw e;
                }
                if (verdict.valid()) {
                    check.log("Manifest signature checked");
                } else {
                    check.failure().log("Manifest signature checked: not valid");
                }
                return verdict;
            }
        }
    }

    private Signature newVerifier() {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA", signatureProvider);
            signature.initVerify(dccManifestKey);
            return signature;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot set up the Manifest verifier", e);
        }
    }
}
