package prepare.edt.duissigner.adapter.kms;

import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.SignatureSpi;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

/**
 * "SHA256withECDSA" whose private-key operation happens in AWS KMS.
 *
 * Signing (as the architecture model says: the digest goes to KMS):
 *   engineUpdate  - the bytes to sign are hashed locally with SHA-256, as they arrive
 *   engineSign    - the 32-byte digest is sent to KMS Sign with MessageType=DIGEST;
 *                   KMS returns a DER-encoded ECDSA signature, which is what JCA callers expect
 *
 * Verifying needs only the public key, so it is done locally by the JDK (SunEC).
 */
final class KmsSignatureSpi extends SignatureSpi {

    private final KmsClient kms;
    private MessageDigest digest;
    private KmsPrivateKey signingKey;
    private Signature localVerifier;

    /** Receives the KMS client from the provider, so the engine can call KMS without a static field. */
    KmsSignatureSpi(KmsClient kms) {
        this.kms = kms;
    }

    /**
     * Gets the engine ready to sign: accepts only a {@link KmsPrivateKey} from this provider's own KeyStore,
     * remembers it, and starts a fresh local SHA-256 digest.
     */
    @Override
    protected void engineInitSign(PrivateKey key) throws InvalidKeyException {
        if (!(key instanceof KmsPrivateKey kmsKey)) {
            throw new InvalidKeyException("EDT-KMS signs only with keys from its own KeyStore, not " + key);
        }
        signingKey = kmsKey;
        localVerifier = null;
        digest = sha256();
    }

    /** Gets the engine ready to verify: creates a local SunEC verifier for the public key, because verifying never needs KMS. */
    @Override
    protected void engineInitVerify(PublicKey key) throws InvalidKeyException {
        try {
            localVerifier = Signature.getInstance("SHA256withECDSA", "SunEC");
            localVerifier.initVerify(key);
            signingKey = null;
        } catch (Exception e) {
            throw new InvalidKeyException("cannot verify locally", e);
        }
    }

    /** Feeds one byte into the signature by passing it to the array version. */
    @Override
    protected void engineUpdate(byte b) throws SignatureException {
        engineUpdate(new byte[] {b}, 0, 1);
    }

    /**
     * Feeds data into the local verifier when verifying, or into the SHA-256 digest when signing,
     * and fails if the engine has not been initialised.
     */
    @Override
    protected void engineUpdate(byte[] bytes, int offset, int length) throws SignatureException {
        if (localVerifier != null) {
            localVerifier.update(bytes, offset, length);
        } else if (digest != null) {
            digest.update(bytes, offset, length);
        } else {
            throw new SignatureException("not initialised");
        }
    }

    /**
     * Finishes the SHA-256 hash and sends the 32-byte digest to KMS Sign (DIGEST, ECDSA_SHA_256), returning the DER
     * signature and wrapping any KMS failure in a SignatureException.
     */
    @Override
    protected byte[] engineSign() throws SignatureException {
        if (signingKey == null) {
            throw new SignatureException("not initialised for signing");
        }
        byte[] hash = digest.digest();   // also resets the digest for the next signature
        try {
            return kms.sign(request -> request
                            .keyId(signingKey.kmsKeyId())
                            .messageType(MessageType.DIGEST)
                            .message(SdkBytes.fromByteArray(hash))
                            .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256))
                    .signature()
                    .asByteArray();
        } catch (RuntimeException e) {
            throw new SignatureException("KMS Sign failed for " + signingKey.kmsKeyId() + ": " + e.getMessage(), e);
        }
    }

    /** Checks the given signature with the local verifier and returns true or false. */
    @Override
    protected boolean engineVerify(byte[] signature) throws SignatureException {
        if (localVerifier == null) {
            throw new SignatureException("not initialised for verifying");
        }
        return localVerifier.verify(signature);
    }

    /** The old JCA hook for setting an algorithm parameter, which this provider does not support. */
    @Override
    @Deprecated
    protected void engineSetParameter(String param, Object value) {
        throw new InvalidParameterException("not supported");
    }

    /** The old JCA hook for reading an algorithm parameter, unsupported for the same reason. */
    @Override
    @Deprecated
    protected Object engineGetParameter(String param) {
        throw new InvalidParameterException("not supported");
    }

    /** Creates a SHA-256 digest, turning the checked exception into an IllegalStateException because every JDK has SHA-256. */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
