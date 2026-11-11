package prepare.edt.dcctools.manifestupload;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.model.Tagging;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Plays the DS&A operator and DCC's Manifest plumbing, producing only what Batch Ingest consumes:
 *
 *   1. Streams the Manifest file to compute its SHA-256 digest (the file is never held in memory).
 *   2. Signs the digest with DCC's Manifest key in KMS (Sign, MessageType DIGEST, ECDSA_SHA_256).
 *   3. Uploads the file to the versioned Manifest bucket, with the signature (base64 DER) as the object tag
 *      "dcc-signature". S3 returns the new object version.                          [CONFIRM format with DCC]
 *   4. Puts an "Object Created" message on the Manifest events queue, in the shape EventBridge gives it.
 *      In production S3 and EventBridge do this; here the tool puts it there directly.
 *
 * Usage: manifest-upload <file> [--bad-signature]
 *   --bad-signature  signs a different digest, so the signature does not match the file (Batch Ingest must reject it)
 *
 * Configuration (environment variables):
 *   EDT_AWS_ENDPOINT        Floci, for example http://floci:4566 (KMS, S3 and SQS on one endpoint)
 *   AWS_REGION              for example eu-west-2
 *   AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY   any non-empty value: Floci does not check them
 *   EDT_MANIFEST_BUCKET     for example edt-manifests
 *   EDT_MANIFEST_QUEUE      for example edt-manifest-events
 *   EDT_MANIFEST_KEY        DCC's Manifest signing key, for example alias/dcc-manifest
 */
public final class ManifestUpload {

    /** The object tag that carries the signature. Placeholder name. [CONFIRM with DCC] */
    static final String SIGNATURE_TAG = "dcc-signature";

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !"--bad-signature".equals(args[1]))) {
            System.err.println("usage: manifest-upload <file> [--bad-signature]");
            System.exit(2);
        }
        Path file = Path.of(args[0]);
        boolean badSignature = args.length == 2;
        String key = file.getFileName().toString();
        String bucket = env("EDT_MANIFEST_BUCKET");

        URI endpoint = URI.create(env("EDT_AWS_ENDPOINT"));
        Region region = Region.of(env("AWS_REGION"));
        try (KmsClient kms = KmsClient.builder().endpointOverride(endpoint).region(region).build();
             S3Client s3 = S3Client.builder().endpointOverride(endpoint).region(region).forcePathStyle(true).build();
             SqsClient sqs = SqsClient.builder().endpointOverride(endpoint).region(region).build()) {

            // 1 and 2: digest the file, KMS signs the digest (the private key never leaves KMS).
            byte[] digest;
            try (InputStream in = Files.newInputStream(file)) {
                digest = sha256(in);
            }
            if (badSignature) {
                digest[0] ^= 1;   // sign something else: the signature will not match the file
            }
            byte[] signature = kms.sign(r -> r
                    .keyId(env("EDT_MANIFEST_KEY"))
                    .message(SdkBytes.fromByteArray(digest))
                    .messageType(MessageType.DIGEST)
                    .signingAlgorithm(SigningAlgorithmSpec.ECDSA_SHA_256)).signature().asByteArray();
            String signatureTag = Base64.getEncoder().encodeToString(signature);

            // 3: upload with the signature as a tag. The SDK URL-encodes the tag (base64 has + / =).
            long size = Files.size(file);
            String versionId = s3.putObject(r -> r
                            .bucket(bucket)
                            .key(key)
                            .contentType("text/csv")
                            .tagging(Tagging.builder()
                                    .tagSet(Tag.builder().key(SIGNATURE_TAG).value(signatureTag).build())
                                    .build()),
                    RequestBody.fromFile(file)).versionId();

            // 4: the message Batch Ingest receives.
            String queueUrl = sqs.getQueueUrl(r -> r.queueName(env("EDT_MANIFEST_QUEUE"))).queueUrl();
            String message = objectCreatedEvent(UUID.randomUUID().toString(), Instant.now(), region.id(),
                    bucket, key, size, versionId);
            String messageId = sqs.sendMessage(r -> r.queueUrl(queueUrl).messageBody(message)).messageId();

            System.out.printf("bucket=%s key=%s version_id=%s size=%d signature=%s sqs_message_id=%s%n",
                    bucket, key, versionId, size, badSignature ? "BAD" : "valid", messageId);
        }
    }

    /** Reads the stream in chunks: the digest of any size of file, in constant memory. */
    static byte[] sha256(InputStream in) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        for (int n; (n = in.read(buffer)) != -1; ) {
            digest.update(buffer, 0, n);
        }
        return digest.digest();
    }

    /**
     * An S3 "Object Created" event as EventBridge delivers it to an SQS target (the message body is the event).
     * Only the fields a consumer needs; the real event has a few more (request-id, requester, etag, ...).
     */
    static String objectCreatedEvent(String id, Instant time, String region, String bucket, String key, long size,
                                     String versionId) {
        return """
                {"version":"0","id":"%s","detail-type":"Object Created","source":"aws.s3","account":"000000000000",\
                "time":"%s","region":"%s","resources":["arn:aws:s3:::%s"],\
                "detail":{"version":"0","bucket":{"name":"%s"},\
                "object":{"key":"%s","size":%d,"version-id":"%s"},"reason":"PutObject"}}"""
                .formatted(id, time, region, json(bucket), json(bucket), json(key), size, json(versionId));
    }

    /** Escapes a value for a JSON string (enough for bucket names, file names and version IDs). */
    static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing environment variable " + name);
        }
        return value;
    }
}
