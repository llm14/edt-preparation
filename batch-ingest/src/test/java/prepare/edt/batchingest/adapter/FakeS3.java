package prepare.edt.batchingest.adapter;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.Tag;

/**
 * S3Client is an interface whose methods throw UnsupportedOperationException by default: this fake answers only the
 * two calls the verifier makes, for one object version.
 */
final class FakeS3 implements S3Client {

    final String versionId;
    final byte[] content;
    final Map<String, String> tags = new HashMap<>();

    FakeS3(String versionId, byte[] content) {
        this.versionId = versionId;
        this.content = content;
    }

    @Override
    public GetObjectTaggingResponse getObjectTagging(GetObjectTaggingRequest request) {
        requireVersion(request.versionId());
        return GetObjectTaggingResponse.builder()
                .tagSet(tags.entrySet().stream().map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build())
                        .toList())
                .build();
    }

    @Override
    public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
        requireVersion(request.versionId());
        return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(content)));
    }

    private void requireVersion(String requested) {
        if (!versionId.equals(requested)) {
            throw new IllegalArgumentException("the verifier must read version " + versionId + ", not " + requested);
        }
    }

    @Override
    public String serviceName() {
        return "s3";
    }

    @Override
    public void close() {
    }
}
