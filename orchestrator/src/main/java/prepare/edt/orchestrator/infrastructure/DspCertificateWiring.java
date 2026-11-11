package prepare.edt.orchestrator.infrastructure;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import prepare.edt.orchestrator.adapter.DspSignatureVerifier;

/**
 * Loads the trusted DSP certificate from the certificate store once, at startup, and builds the verifier with it.
 * A missing store or certificate stops the application: it must never run without checking the DSP's signature.
 */
@ApplicationScoped
public class DspCertificateWiring {

    @Startup   // load at startup, not on first use: a missing certificate stops the application at once
    @Produces
    @Singleton
    DspSignatureVerifier dspSignatureVerifier(DspConfig config) throws Exception {
        // JCA: a KeyStore of type PKCS12 from the default provider. The store holds certificates only.
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(config.certificateStore()))) {
            store.load(in, config.certificateStorePassword().toCharArray());
        }
        if (!(store.getCertificate(config.certificateAlias()) instanceof X509Certificate certificate)) {
            throw new IllegalStateException("no DSP certificate '" + config.certificateAlias() + "' in "
                    + config.certificateStore());
        }
        try (var fields = LogFields.of(
                "event.action", "dsp-certificate-loaded",
                "edt.certificate_store", config.certificateStore(),
                "edt.dsp_certificate_subject", certificate.getSubjectX500Principal().getName())) {
            org.jboss.logging.Logger.getLogger(DspCertificateWiring.class).info("Trusted DSP certificate loaded");
        }
        return new DspSignatureVerifier(certificate);
    }
}
