package prepare.edt.duissigner.adapter.kms;

import java.security.Provider;
import java.util.Map;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Our own JCA provider, named "EDT-KMS". It offers two engines:
 *
 *   KeyStore  "KMS"              -> KmsKeyStoreSpi   (certificates from the store, keys as KMS handles)
 *   Signature "SHA256withECDSA"  -> KmsSignatureSpi  (the digest is signed by AWS KMS)
 *
 * Install it once at startup with Security.addProvider(new KmsProvider(...)), then use plain JCA:
 *   KeyStore.getInstance("KMS", "EDT-KMS"), Signature.getInstance("SHA256withECDSA", "EDT-KMS").
 * Nothing else in the application knows that the keys are in KMS.
 *
 * Each engine is registered as a Provider.Service whose newInstance() creates the SPI object.
 * That is how the provider hands its KmsClient to the SPIs, instead of a static field.
 */
public final class KmsProvider extends Provider {

    public static final String NAME = "EDT-KMS";

    /**
     * @param kms            the KMS client (endpoint and region decided by the caller)
     * @param kmsAliasPrefix prefix that turns a KeyStore alias into a KMS alias, for example "alias/edt-"
     */
    public KmsProvider(KmsClient kms, String kmsAliasPrefix) {
        super(NAME, "1.0", "EDT: KeyStore and SHA256withECDSA backed by AWS KMS");

        putService(new Service(this, "KeyStore", "KMS", KmsKeyStoreSpi.class.getName(), null, null) {
            @Override
            public Object newInstance(Object parameter) {
                return new KmsKeyStoreSpi(kmsAliasPrefix);
            }
        });

        // SupportedKeyClasses: JCA picks this provider for KmsPrivateKey even when no provider is named.
        putService(new Service(this, "Signature", "SHA256withECDSA", KmsSignatureSpi.class.getName(), null,
                Map.of("SupportedKeyClasses", KmsPrivateKey.class.getName())) {
            @Override
            public Object newInstance(Object parameter) {
                return new KmsSignatureSpi(kms);
            }
        });
    }
}
