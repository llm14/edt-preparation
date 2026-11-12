package prepare.edt.orchestrator.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.net.URI;
import prepare.edt.orchestrator.adapter.ManifestReaderActivityImpl;
import prepare.edt.orchestrator.adapter.S3ManifestBytes;
import prepare.edt.orchestrator.usecase.ReadManifestPage;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Builds the S3 client and the Manifest Reader activity. */
@ApplicationScoped
public class ManifestWiring {

    @Produces
    @Singleton
    S3Client s3(AwsConfig aws) {
        var builder = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(aws.region()))
                // Path-style (endpoint/bucket/key) only for an emulator: it has no DNS name per bucket.
                .forcePathStyle(aws.endpoint().isPresent());
        aws.endpoint().ifPresent(endpoint -> builder.endpointOverride(URI.create(endpoint)));
        if (aws.accessKeyId().isPresent() && aws.secretAccessKey().isPresent()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(aws.accessKeyId().get(), aws.secretAccessKey().get())));
        }
        return builder.build();
    }

    @Produces
    @Singleton
    ManifestReaderActivityImpl manifestReader(S3Client s3, BatchConfig batch) {
        return new ManifestReaderActivityImpl(new ReadManifestPage(new S3ManifestBytes(s3), batch.readChunkBytes()));
    }

    void close(@Disposes S3Client s3) {
        s3.close();
    }
}
