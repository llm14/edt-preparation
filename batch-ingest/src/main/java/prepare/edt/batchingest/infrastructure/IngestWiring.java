package prepare.edt.batchingest.infrastructure;

import io.temporal.client.WorkflowClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.net.URI;
import java.security.Security;
import org.jboss.logging.Logger;
import prepare.edt.batchingest.adapter.S3ManifestVerifier;
import prepare.edt.batchingest.adapter.SqsManifestEventConsumer;
import prepare.edt.batchingest.adapter.TemporalBatchStarter;
import prepare.edt.batchingest.adapter.kms.KmsProvider;
import prepare.edt.batchingest.adapter.kms.KmsPublicKey;
import prepare.edt.batchingest.usecase.IngestManifest;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Builds the AWS clients, installs the EDT-KMS JCA provider and assembles the use case and its adapters.
 *
 * The use case and the adapters have no CDI annotations: producer methods create them (Clean Architecture).
 */
@ApplicationScoped
public class IngestWiring {

    private static final Logger LOG = Logger.getLogger(IngestWiring.class);

    @Produces
    @Singleton
    SqsClient sqs(AwsConfig aws) {
        return configure(SqsClient.builder().httpClient(UrlConnectionHttpClient.create()), aws).build();
    }

    @Produces
    @Singleton
    S3Client s3(AwsConfig aws) {
        // Path-style (endpoint/bucket/key) only for an emulator: it has no DNS name per bucket.
        return configure(S3Client.builder().httpClient(UrlConnectionHttpClient.create()), aws).forcePathStyle(aws.endpoint().isPresent()).build();
    }

    @Produces
    @Singleton
    KmsClient kms(AwsConfig aws) {
        return configure(KmsClient.builder().httpClient(UrlConnectionHttpClient.create()), aws).build();
    }

    @Produces
    @Singleton
    SqsManifestEventConsumer consumer(SqsClient sqs, S3Client s3, KmsClient kms, WorkflowClient temporal,
                                      ManifestConfig manifest, TemporalConfig temporalConfig) {
        if (Security.getProvider(KmsProvider.NAME) == null) {
            Security.addProvider(new KmsProvider(kms));
        }
        var verifier = new S3ManifestVerifier(s3, manifest.signatureTag(), KmsProvider.NAME,
                new KmsPublicKey(manifest.signingKey()));
        var starter = new TemporalBatchStarter(temporal, temporalConfig.batchTaskQueue());

        try (var fields = LogFields.of("event.action", "manifest-consumer-configured",
                "edt.manifest.queue", manifest.queue(), "edt.manifest.signing_key", manifest.signingKey(),
                "edt.signature_provider", KmsProvider.NAME, "edt.batch_task_queue", temporalConfig.batchTaskQueue())) {
            LOG.info("Manifest consumer configured");
        }
        return new SqsManifestEventConsumer(sqs, manifest.queue(), new IngestManifest(verifier, starter));
    }

    void close(@Disposes SqsClient client) {
        client.close();
    }

    void close(@Disposes S3Client client) {
        client.close();
    }

    void close(@Disposes KmsClient client) {
        client.close();
    }

    /** Same settings for the three clients: region, and for Floci its endpoint and keys. */
    private static <B extends AwsClientBuilder<B, ?>> B configure(B builder, AwsConfig aws) {
        builder.region(Region.of(aws.region()));
        aws.endpoint().ifPresent(endpoint -> builder.endpointOverride(URI.create(endpoint)));
        if (aws.accessKeyId().isPresent() && aws.secretAccessKey().isPresent()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(aws.accessKeyId().get(), aws.secretAccessKey().get())));
        }
        return builder;
    }
}
