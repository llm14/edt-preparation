package prepare.edt.orchestrator.infrastructure;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Where the trusted DSP signing certificate is (prefix "edt.dsp"): the certificate store that DCC mounts read-only.
 * In local development, dcc-tools/kms-setup writes it.
 */
@ConfigMapping(prefix = "edt.dsp")
public interface DspConfig {

    /** The PKCS#12 certificate store (property "certificate-store"). */
    String certificateStore();

    /** Its password (property "certificate-store-password"). */
    String certificateStorePassword();

    /** The alias of the DSP signing certificate in the store (property "certificate-alias"). */
    @WithDefault("dsp")
    String certificateAlias();
}
