package prepare.edt.batchingest.infrastructure;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * How to reach SQS, S3 and KMS (prefix "edt.aws").
 *
 * Locally: Floci, which serves all three on one endpoint and accepts any credentials. In production: no endpoint
 * (real AWS) and no credentials here: the AWS SDK takes them from the pod's IAM role.
 */
@ConfigMapping(prefix = "edt.aws")
public interface AwsConfig {

    /** For example http://localhost:4566 (Floci). Empty means real AWS. */
    Optional<String> endpoint();

    @WithDefault("eu-west-2")
    String region();

    /** Static credentials for local emulators only. Leave empty to use the AWS SDK's default chain. */
    Optional<String> accessKeyId();

    Optional<String> secretAccessKey();
}
