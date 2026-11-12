package prepare.edt.duissigner.infrastructure;

import io.smallrye.config.ConfigMapping;
import java.util.Map;
import java.util.Optional;

/**
 * Which keys and which JCA providers the signer uses (prefix "edt.signing").
 *
 * KMS (default):   keystore-type=KMS,    keystore-provider=EDT-KMS, signature-provider=EDT-KMS
 * Software keys:   keystore-type=PKCS12, keystore-provider=SUN,     signature-provider=SunEC
 */
@ConfigMapping(prefix = "edt.signing")
public interface SigningConfig {

    /** JCA KeyStore type, for example KMS or PKCS12. */
    String keystoreType();

    /** JCA provider of the KeyStore, for example EDT-KMS or SUN. */
    String keystoreProvider();

    /** JCA provider of the Signature engine, for example EDT-KMS or SunEC. */
    String signatureProvider();

    /** The file loaded into the KeyStore: the certificate store (KMS) or the PKCS#12 key file (software). */
    String certificateStore();

    String certificateStorePassword();

    /** Password of the private keys. None for KMS: the keys are not in the file. */
    Optional<String> keyPassword();

    /** KeyStore alias of each User Role, keyed by role name: other-user, registered-supplier-agent. */
    Map<String, String> aliases();
}
