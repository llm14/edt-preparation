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

    KmsSignatureSpi(KmsClient kms) {
        this.kms = kms;
    }

    @Override
    protected void engineInitSign(PrivateKey key) throws InvalidKeyException {
        if (!(key instanceof KmsPrivateKey kmsKey)) {
            throw new InvalidKeyException("EDT-KMS signs only with keys from its own KeyStore, not " + key);
        }
        signingKey = kmsKey;
        localVerifier = null;
        digest = sha256();
    }

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

    @Override
    protected void engineUpdate(byte b) throws SignatureException {
        engineUpdate(new byte[] {b}, 0, 1);
    }

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

    @Override
    protected boolean engineVerify(byte[] signature) throws SignatureException {
        if (localVerifier == null) {
            throw new SignatureException("not initialised for verifying");
        }
        return localVerifier.verify(signature);
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
