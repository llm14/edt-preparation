package prepare.edt.orchestrator.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import prepare.edt.orchestrator.usecase.ReadManifestPage;
import prepare.edt.orchestrator.usecase.ReadManifestPageTest;

/** The Manifest Reader writes one interaction line for each Page it reads from the Manifest Bucket. */
class ManifestReaderActivityImplTest {

    final ManifestReaderActivityImpl reader = new ManifestReaderActivityImpl(
            new ReadManifestPage(new ReadManifestPageTest.InMemory(ReadManifestPageTest.CSV), 16));

    @Test
    void a_page_read_is_one_interaction_line_with_the_common_keys_and_a_duration() {
        try (LogCapture log = new LogCapture()) {
            var page = reader.readPage(new ManifestReaderActivity.Request("edt-manifests", "sample.csv", "v1", 0, 2, 1));

            var lines = log.lines("edt.interaction");
            assertEquals(1, lines.size());
            var fields = lines.get(0).fields();
            assertEquals("manifest-page-read", fields.get("event.action"));
            assertEquals("edt.orchestratorReqS3", fields.get("edt.interface"));
            assertEquals("outbound", fields.get("network.direction"));
            assertEquals("success", fields.get("event.outcome"));
            assertTrue(Long.parseLong(fields.get("event.duration")) >= 0);
            assertEquals(String.valueOf(page.rows().size()), fields.get("edt.manifest.rows"));
            assertEquals(String.valueOf(page.nextOffset()), fields.get("edt.manifest.next_offset"));
        }
    }

    @Test
    void an_invalid_row_line_keeps_its_own_action_and_the_page_line_keeps_its_own() {
        var csv = ReadManifestPageTest.CSV.replace("00-DB-12-34-56-78-90-A1,ESME", "not-a-device,ESME");
        var withInvalid = new ManifestReaderActivityImpl(
                new ReadManifestPage(new ReadManifestPageTest.InMemory(csv), 16));
        try (LogCapture log = new LogCapture()) {
            withInvalid.readPage(new ManifestReaderActivity.Request("edt-manifests", "sample.csv", "v1", 0, 5, 1));

            assertEquals("manifest-row-invalid",
                    log.lines(ManifestReaderActivityImpl.class).get(0).fields().get("event.action"));
            var invalid = log.lines(ManifestReaderActivityImpl.class).get(0);
            assertTrue(invalid.fields().containsKey("error.message"), "the reason is a field");
            assertEquals(null, invalid.thrown(), "an expected rejection carries no stack");
            var page = log.lines("edt.interaction").get(0).fields();
            assertEquals("manifest-page-read", page.get("event.action"));
            assertFalse(log.lines(ManifestReaderActivityImpl.class).get(0).fields().containsKey("edt.interface"));
        }
    }
}
