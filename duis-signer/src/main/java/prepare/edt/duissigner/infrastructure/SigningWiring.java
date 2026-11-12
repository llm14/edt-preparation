package prepare.edt.duissigner.infrastructure;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Security;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;
import prepare.edt.duissigner.adapter.EcsAuditLog;
import prepare.edt.duissigner.adapter.JcaXmlSigner;
import prepare.edt.duissigner.adapter.XmlServiceRequestReader;
import prepare.edt.duissigner.adapter.kms.KmsProvider;
import prepare.edt.duissigner.entity.SigningPolicy;
import prepare.edt.duissigner.entity.UserRole;
import prepare.edt.duissigner.usecase.SignServiceRequest;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Builds the signing use case from configuration: this is where the JCA provider is chosen.
 *
 *   1. if the configuration names EDT-KMS, install our KMS provider (Security.addProvider)
 *   2. KeyStore.getInstance(keystore-type, keystore-provider), loaded from the certificate store file
 *   3. the adapters: XML reader, JcaXmlSigner (with signature-provider), audit log
 *   4. the policy from the allowlists
 *   5. the use case, with all of them
 *
 * The use case and the adapters have no CDI annotations: a producer method creates them (Clean Architecture).
 */
@ApplicationScoped
public class SigningWiring {

    private static final Logger LOG = Logger.getLogger(SigningWiring.class);

    private KmsClient kmsClient;

    @Produces
    @Singleton
    SignServiceRequest signServiceRequest(SigningConfig signing, KmsConfig kms, PolicyConfig policy) throws Exception {
        if (usesKms(signing)) {
            installKmsProvider(kms);
        }
        KeyStore keyStore = loadKeyStore(signing);

        var signer = new JcaXmlSigner(keyStore,
                signing.keyPassword().map(String::toCharArray).orElse(null),
                aliases(signing),
                signing.signatureProvider());

        try (var fields = LogFields.of(
                "event.action", "signer-configured",
                "edt.keystore_type", signing.keystoreType(),
                "edt.keystore_provider", signing.keystoreProvider(),
                "edt.signature_provider", signing.signatureProvider(),
                "edt.certificate_store", signing.certificateStore())) {
            LOG.info("Signer configured");
        }

        return new SignServiceRequest(new XmlServiceRequestReader(), signingPolicy(policy), signer, new EcsAuditLog());
    }

    @PreDestroy
    void close() {
        if (kmsClient != null) {
            kmsClient.close();
        }
    }

    private static boolean usesKms(SigningConfig signing) {
        return KmsProvider.NAME.equals(signing.keystoreProvider()) || KmsProvider.NAME.equals(signing.signatureProvider());
    }

    private void installKmsProvider(KmsConfig config) {
        var builder = KmsClient.builder()
                .region(Region.of(config.region()))
                .httpClient(UrlConnectionHttpClient.create());
        config.endpoint().ifPresent(endpoint -> builder.endpointOverride(URI.create(endpoint)));
        if (config.accessKeyId().isPresent() && config.secretAccessKey().isPresent()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.accessKeyId().get(), config.secretAccessKey().get())));
        }
        kmsClient = builder.build();

        if (Security.getProvider(KmsProvider.NAME) == null) {
            Security.addProvider(new KmsProvider(kmsClient, config.aliasPrefix()));
        }
    }

    private static KeyStore loadKeyStore(SigningConfig signing) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(signing.keystoreType(), signing.keystoreProvider());
        try (InputStream in = Files.newInputStream(Path.of(signing.certificateStore()))) {
            keyStore.load(in, signing.certificateStorePassword().toCharArray());
        }
        return keyStore;
    }

    /** "other-user" -> OTHER_USER, and so on. */
    private static Map<UserRole, String> aliases(SigningConfig signing) {
        Map<UserRole, String> aliases = new EnumMap<>(UserRole.class);
        signing.aliases().forEach((role, alias) -> aliases.put(role(role), alias));
        return aliases;
    }

    private static SigningPolicy signingPolicy(PolicyConfig config) {
        Map<UserRole, SigningPolicy.RolePolicy> roles = new EnumMap<>(UserRole.class);
        config.roles().forEach((name, role) -> {
            Map<String, Set<String>> srvs = new HashMap<>();
            role.srvs().forEach((srv, deviceTypes) -> srvs.put(srv, new HashSet<>(deviceTypes)));
            roles.put(role(name), new SigningPolicy.RolePolicy(role.userId(), srvs));
        });
        return new SigningPolicy(roles);
    }

    private static UserRole role(String name) {
        return UserRole.valueOf(name.toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
