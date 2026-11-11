package prepare.edt.batchingest.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import prepare.edt.batchingest.entity.ManifestRef;
import prepare.edt.batchingest.usecase.IngestManifest.Outcome;
import prepare.edt.batchingest.usecase.ManifestVerifier.Verdict;

class IngestManifestTest {

    static final ManifestRef MANIFEST = new ManifestRef("edt-manifests", "sample.csv", "v1");

    final List<ManifestRef> started = new ArrayList<>();

    BatchStarter starter(boolean firstTime) {
        return manifest -> {
            started.add(manifest);
            return firstTime;
        };
    }

    @Test
    void a_manifest_signed_by_dcc_starts_its_batch() {
        var outcome = new IngestManifest(m -> Verdict.signedByDcc(), starter(true)).ingest(MANIFEST);

        assertInstanceOf(Outcome.Started.class, outcome);
        assertEquals(List.of(MANIFEST), started);
    }

    @Test
    void an_invalid_signature_is_rejected_and_no_batch_starts() {
        var outcome = new IngestManifest(m -> Verdict.invalid("signature does not match the Manifest"), starter(true))
                .ingest(MANIFEST);

        assertEquals(new Outcome.Rejected("signature does not match the Manifest"), outcome);
        assertEquals(List.of(), started);
    }

    @Test
    void a_message_delivered_twice_finds_the_batch_already_started() {
        var outcome = new IngestManifest(m -> Verdict.signedByDcc(), starter(false)).ingest(MANIFEST);

        assertInstanceOf(Outcome.AlreadyStarted.class, outcome);
    }

    @Test
    void when_kms_or_s3_cannot_be_reached_the_error_goes_to_the_caller() {
        var ingest = new IngestManifest(m -> {
            throw new IllegalStateException("KMS unreachable");
        }, starter(true));

        assertThrows(IllegalStateException.class, () -> ingest.ingest(MANIFEST));
        assertEquals(List.of(), started);
    }

    @Test
    void the_batch_workflow_id_comes_from_bucket_key_and_version() {
        assertEquals("batch-edt-manifests/sample.csv/v1", MANIFEST.batchWorkflowId());
        assertThrows(IllegalArgumentException.class, () -> new ManifestRef("edt-manifests", "sample.csv", null));
    }
}
