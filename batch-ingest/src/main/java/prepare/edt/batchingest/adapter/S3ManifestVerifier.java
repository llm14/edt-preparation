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

    @Override
    public Verdict verify(ManifestRef manifest) {
        Optional<String> tag = s3.getObjectTagging(r -> r
                        .bucket(manifest.bucket()).key(manifest.key()).versionId(manifest.versionId()))
                .tagSet().stream()
                .filter(t -> signatureTag.equals(t.key()))
                .map(Tag::value)
                .findFirst();
        if (tag.isEmpty()) {
            return Verdict.invalid("no " + signatureTag + " tag");
        }
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(tag.get());
        } catch (IllegalArgumentException e) {
            return Verdict.invalid("the " + signatureTag + " tag is not base64");
        }

        Signature signature = newVerifier();
        try (InputStream in = s3.getObject(r -> r
                .bucket(manifest.bucket()).key(manifest.key()).versionId(manifest.versionId()))) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) != -1; ) {
                signature.update(buffer, 0, n);
            }
            return signature.verify(signatureBytes)
                    ? Verdict.signedByDcc()
                    : Verdict.invalid("signature does not match the Manifest");
        } catch (SignatureException e) {
            // SunEC: the tag is not a valid DER ECDSA signature.
            return Verdict.invalid("signature is malformed");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the Manifest from S3", e);
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
