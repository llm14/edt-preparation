package prepare.edt.batchingest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import prepare.edt.batchingest.adapter.ObjectCreatedEvent.UnreadableEventException;
import prepare.edt.batchingest.entity.ManifestRef;

class ObjectCreatedEventTest {

    /** As dcc-tools/manifest-upload puts it on the queue (the shape EventBridge gives an S3 Object Created event). */
    static final String EVENT = """
            {"version":"0","id":"e1","detail-type":"Object Created","source":"aws.s3","account":"000000000000",\
            "time":"2026-11-11T10:00:00Z","region":"eu-west-2","resources":["arn:aws:s3:::edt-manifests"],\
            "detail":{"version":"0","bucket":{"name":"edt-manifests"},\
            "object":{"key":"sample.csv","size":42,"version-id":"v1"},"reason":"PutObject"}}""";

    @Test
    void reads_bucket_key_and_version() throws Exception {
        assertEquals(new ManifestRef("edt-manifests", "sample.csv", "v1"), ObjectCreatedEvent.parse(EVENT));
    }

    @Test
    void without_a_version_it_cannot_be_read() {
        // A bucket without versioning gives no version-id: the Batch ID could not tell two uploads apart.
        assertThrows(UnreadableEventException.class,
                () -> ObjectCreatedEvent.parse(EVENT.replace(",\"version-id\":\"v1\"", "")));
    }

    @Test
    void other_messages_cannot_be_read() {
        assertThrows(UnreadableEventException.class, () -> ObjectCreatedEvent.parse("not json"));
        assertThrows(UnreadableEventException.class,
                () -> ObjectCreatedEvent.parse(EVENT.replace("Object Created", "Object Deleted")));
    }
}
