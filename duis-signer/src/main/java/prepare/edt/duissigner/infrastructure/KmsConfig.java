package prepare.edt.duissigner.infrastructure;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * How to reach AWS KMS (prefix "edt.kms"). Only used when the EDT-KMS provider is configured.
 *
 * Locally: Floci, with any non-empty credentials. In production: no endpoint (real AWS) and no credentials here:
 * the AWS SDK takes them from the pod's IAM role, as the deployment model says.
 */
@ConfigMapping(prefix = "edt.kms")
public interface KmsConfig {

    /** KMS endpoint, for example http://localhost:4566 (Floci). Empty means real AWS. */
    Optional<String> endpoint();

    @WithDefault("eu-west-2")
    String region();

    /** Prefix that turns a KeyStore alias into a KMS alias: "other-user" becomes "alias/edt-other-user". */
    @WithDefault("alias/edt-")
    String aliasPrefix();

    /** Static credentials for local emulators only. Leave empty to use the AWS SDK's default chain. */
    Optional<String> accessKeyId();

    Optional<String> secretAccessKey();
}
