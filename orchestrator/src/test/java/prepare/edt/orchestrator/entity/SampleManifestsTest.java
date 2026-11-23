package prepare.edt.orchestrator.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The sample Manifests in samples/manifests must parse with the same rules the Orchestrator applies. */
class SampleManifestsTest {

    static final Path SAMPLES = Path.of("..", "samples", "manifests");

    @Test
    void three_types_has_an_esme_a_gsme_and_a_chf() throws Exception {
        List<String> lines = Files.readAllLines(SAMPLES.resolve("three-types.csv"));
        assertEquals(ManifestRow.HEADER, lines.get(0));

        List<ManifestRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            rows.add(ManifestRow.parse(i, lines.get(i)));
        }

        assertEquals(3, rows.size());
        assertEquals(List.of("ESME", "GSME", "CHF"), rows.stream().map(ManifestRow::deviceType).toList());
    }
}
