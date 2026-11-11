package prepare.edt.dcctools.manifestupload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ManifestUploadTest {

    @Test
    void the_streamed_digest_is_the_sha256_of_the_file() throws Exception {
        byte[] digest = ManifestUpload.sha256(new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)));

        // The SHA-256 test vector for "abc" (FIPS 180-2).
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HexFormat.of().formatHex(digest));
    }

    @Test
    void the_message_has_the_bucket_key_and_version_where_eventbridge_puts_them() {
        String event = ManifestUpload.objectCreatedEvent("e1", Instant.parse("2026-11-11T10:00:00Z"), "eu-west-2",
                "edt-manifests", "sample.csv", 42, "v1");

        assertEquals("""
                {"version":"0","id":"e1","detail-type":"Object Created","source":"aws.s3","account":"000000000000",\
                "time":"2026-11-11T10:00:00Z","region":"eu-west-2","resources":["arn:aws:s3:::edt-manifests"],\
                "detail":{"version":"0","bucket":{"name":"edt-manifests"},\
                "object":{"key":"sample.csv","size":42,"version-id":"v1"},"reason":"PutObject"}}""", event);
    }
}
