package prepare.edt.duissigner.adapter.kms;

import java.security.PrivateKey;
import java.security.interfaces.ECKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;

/**
 * An opaque handle to a private key that lives in AWS KMS.
 *
 * It holds no key material: only the KMS key id (an alias such as "alias/edt-other-user").
 * getEncoded() returns null, which is how JCA says "this key cannot be exported".
 * Only our KmsSignatureSpi knows what to do with it: ask KMS to sign.
 *
 * It implements ECKey, with the curve taken from the role's certificate, so code that checks
 * the key size (for example the JDK's XML DSig) sees a 256-bit EC key.
 */
public final class KmsPrivateKey implements PrivateKey, ECKey {

    private final String kmsKeyId;
    private final ECParameterSpec params;

    KmsPrivateKey(String kmsKeyId, ECPublicKey publicKey) {
        this.kmsKeyId = kmsKeyId;
        this.params = publicKey.getParams();
    }

    /** The KMS key id or alias to send to the KMS Sign operation. */
    public String kmsKeyId() {
        return kmsKeyId;
    }

    @Override
    public String getAlgorithm() {
        return "EC";
    }

    /** No encoding: the key material never leaves KMS. */
    @Override
    public String getFormat() {
        return null;
    }

    @Override
    public byte[] getEncoded() {
        return null;
    }

    @Override
    public ECParameterSpec getParams() {
        return params;
    }

    @Override
    public String toString() {
        return "KmsPrivateKey[" + kmsKeyId + "]";
    }
}
