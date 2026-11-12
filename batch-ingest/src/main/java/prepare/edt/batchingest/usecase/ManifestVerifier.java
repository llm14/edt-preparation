package prepare.edt.batchingest.usecase;

import prepare.edt.batchingest.entity.ManifestRef;

/**
 * Port: checks DCC's signature of a Manifest (model component "Manifest Signature Verifier").
 *
 * Returns a Verdict for a Manifest that can be read; throws an unchecked exception when S3 or KMS cannot be reached,
 * so the caller retries later instead of rejecting a good Manifest.
 */
public interface ManifestVerifier {

    Verdict verify(ManifestRef manifest);

    record Verdict(boolean valid, String reason) {

        public static Verdict signedByDcc() {
            return new Verdict(true, null);
        }

        public static Verdict invalid(String reason) {
            return new Verdict(false, reason);
        }
    }
}
