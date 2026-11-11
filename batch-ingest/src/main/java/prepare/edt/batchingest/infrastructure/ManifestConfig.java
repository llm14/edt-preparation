package prepare.edt.batchingest.infrastructure;

import io.smallrye.config.ConfigMapping;

/** The Manifest settings (prefix "edt.manifest"). */
@ConfigMapping(prefix = "edt.manifest")
public interface ManifestConfig {

    /** Name of the Manifest Events queue. */
    String queue();

    /** DCC's Manifest signing key in KMS, as a key id or alias (for example alias/dcc-manifest). [CONFIRM] */
    String signingKey();

    /** The S3 object tag that carries the signature. [CONFIRM] the name and format with DCC. */
    String signatureTag();
}
