package prepare.edt.batchingest.adapter.kms;

import java.security.PublicKey;

/**
 * A handle to a public key in AWS KMS: only the key id or alias, for example "alias/dcc-manifest".
 *
 * The public key could be downloaded, but the architecture model says Batch Ingest checks the signature with the KMS
 * Verify operation, so the verification happens in KMS and this handle carries no key material.
 */
public final class KmsPublicKey implements PublicKey {

    private final String kmsKeyId;

    public KmsPublicKey(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public String kmsKeyId() {
        return kmsKeyId;
    }

    @Override
    public String getAlgorithm() {
        return "EC";
    }

    @Override
    public String getFormat() {
        return null;
    }

    @Override
    public byte[] getEncoded() {
        return null;
    }

    @Override
    public String toString() {
        return "KmsPublicKey[" + kmsKeyId + "]";
    }
}
