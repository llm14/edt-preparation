package prepare.edt.batchingest.adapter.kms;

import java.security.Provider;
import java.util.Map;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Batch Ingest's own JCA provider "EDT-KMS" (its own copy: no shared module with the DUIS Signer).
 * One engine only:
 *
 *   Signature "SHA256withECDSA" -> KmsVerifySignatureSpi (verify with KMS Verify; signing refused)
 *
 * Install it once with Security.addProvider(new KmsProvider(kms)), then plain JCA:
 *   Signature s = Signature.getInstance("SHA256withECDSA", "EDT-KMS");
 *   s.initVerify(new KmsPublicKey("alias/dcc-manifest")); s.update(...); s.verify(signature);
 */
public final class KmsProvider extends Provider {

    public static final String NAME = "EDT-KMS";

    public KmsProvider(KmsClient kms) {
        super(NAME, "1.0", "EDT Batch Ingest: SHA256withECDSA verified by AWS KMS");

        // SupportedKeyClasses: JCA picks this provider for a KmsPublicKey even when no provider is named.
        putService(new Service(this, "Signature", "SHA256withECDSA", KmsVerifySignatureSpi.class.getName(), null,
                Map.of("SupportedKeyClasses", KmsPublicKey.class.getName())) {
            @Override
            public Object newInstance(Object parameter) {
                return new KmsVerifySignatureSpi(kms);
            }
        });
    }
}
