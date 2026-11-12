package prepare.edt.orchestrator.adapter;

import prepare.edt.orchestrator.entity.ManifestLocation;
import prepare.edt.orchestrator.usecase.ManifestBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 byte-range reads: GetObject of one version with "Range: bytes=start-end" (both ends included).
 * S3 answers 416 (Range Not Satisfiable) for a range that starts after the end: that means no more bytes.
 */
public final class S3ManifestBytes implements ManifestBytes {

    private final S3Client s3;

    public S3ManifestBytes(S3Client s3) {
        this.s3 = s3;
    }

    @Override
    public byte[] read(ManifestLocation manifest, long start, int length) {
        try {
            return s3.getObjectAsBytes(r -> r
                    .bucket(manifest.bucket())
                    .key(manifest.key())
                    .versionId(manifest.versionId())
                    .range("bytes=" + start + "-" + (start + length - 1))).asByteArray();
        } catch (S3Exception e) {
            if (e.statusCode() == 416) {
                return new byte[0];
            }
            throw e;
        }
    }
}
