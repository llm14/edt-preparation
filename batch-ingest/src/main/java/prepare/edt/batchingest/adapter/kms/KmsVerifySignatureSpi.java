package prepare.edt.batchingest.adapter.kms;

import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.ProviderException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.SignatureSpi;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KmsInvalidSignatureException;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

/**
 * "SHA256withECDSA" verified by AWS KMS (model: "checks the signature ... with the AWS KMS Verify operation").
 *
 *   engineUpdate  - the bytes are hashed locally with SHA-256 as they arrive, so a Manifest of any size is streamed
 *   engineVerify  - the 32-byte digest and the signature go to KMS Verify with MessageType=DIGEST
 *
 * KMS answers a wrong signature with KMSInvalidSignatureException, not with "false": that becomes false here.
 * Any other KMS error (unreachable, throttled, no permission) is a ProviderException: it is not a verdict on the
 * Manifest, so the caller must not reject the Manifest for it.
 *
 * Batch Ingest never signs: initSign is refused.
 */
final class KmsVerifySignatureSpi extends SignatureSpi {

    private final KmsClient kms;
    private MessageDigest digest;
    private KmsPublicKey key;

    KmsVerifySignatureSpi(KmsClient kms) {
        this.kms = kms;
    }

    @Override
    protected void engineInitVerify(PublicKey publicKey) throws InvalidKeyException {
        if (!(publicKey instanceof KmsPublicKey kmsKey)) {
            throw new InvalidKeyException("EDT-KMS verifies only with a KmsPublicKey, not " + publicKey);
        }
        key = kmsKey;
        digest = sha256();
    }

    @Override
    protected void engineInitSign(PrivateKey privateKey) throws InvalidKeyException {
        throw new InvalidKeyException("Batch Ingest only verifies; it never signs");
    }

    @Override
    protected void engineUpdate(byte b) throws SignatureException {
        engineUpdate(new byte[] {b}, 0, 1);
    }

    @Override
    protected void engineUpdate(byte[] bytes, int offset, int length) throws SignatureException {
        if (digest == null) {
            throw new SignatureException("not initialised for verifying");
        }
        digest.update(bytes, offset, length);
    }

    @Override
    protected byte[] engineSign() throws SignatureException {
        throw new SignatureException("Batch Ingest only verifies; it never signs");
    }

    @Override
    protected boolean engineVerify(byte[] signature) throws SignatureException {
        if (digest == null) {
            throw new SignatureException("not initialised for verifying");
        }
        byte[] hash = digest.digest();   // also resets the digest for the next verification
        try {
            return kms.verify(request -> request
                            .keyId(key.kmsKeyId())
                            .messageType(MessageType.DIGEST)
                            .message(SdkBytes.fromByteArray(hash))
                            .signature(SdkBytes.fromByteArray(signature))
                            .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256))
                    .signatureValid();
        } catch (KmsInvalidSignatureException e) {
            return false;
        } catch (RuntimeException e) {
            throw new ProviderException("KMS Verify failed for " + key.kmsKeyId() + ": " + e.getMessage(), e);
        }
    }

    @Override
    @Deprecated
    protected void engineSetParameter(String param, Object value) {
        throw new InvalidParameterException("not supported");
    }

    @Override
    @Deprecated
    protected Object engineGetParameter(String param) {
        throw new InvalidParameterException("not supported");
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
