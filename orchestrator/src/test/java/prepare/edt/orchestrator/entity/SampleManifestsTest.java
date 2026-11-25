package prepare.edt.orchestrator.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The sample Manifests in samples/manifests must parse with the same rules the Orchestrator applies, and keep their purpose. */
class SampleManifestsTest {

    static final Path SAMPLES = Path.of("..", "samples", "manifests");

    static List<ManifestRow> rows(String file) throws Exception {
        List<String> lines = Files.readAllLines(SAMPLES.resolve(file));
        assertEquals(ManifestRow.HEADER, lines.get(0));
        List<ManifestRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            rows.add(ManifestRow.parse(i, lines.get(i)));
        }
        return rows;
    }

    @Test
    void one_device_is_one_responding_esme() throws Exception {
        var rows = rows("one-device.csv");

        assertEquals(1, rows.size());
        assertEquals("ESME", rows.get(0).deviceType());
        assertTrue(rows.get(0).deviceId().endsWith("-A0"));
    }

    @Test
    void device_types_has_an_esme_a_gsme_and_a_chf_all_responding() throws Exception {
        var rows = rows("device-types.csv");

        assertEquals(List.of("ESME", "GSME", "CHF"), rows.stream().map(ManifestRow::deviceType).toList());
        assertTrue(rows.stream().allMatch(r -> r.deviceId().endsWith("-A0")));
    }

    @Test
    void device_behaviours_has_one_device_per_behaviour_a0_to_a7() throws Exception {
        var rows = rows("device-behaviours.csv");

        assertEquals(List.of("A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7"),
                rows.stream().map(r -> r.deviceId().substring(r.deviceId().length() - 2)).toList());
    }

    @Test
    void every_row_names_a_flow_in_the_catalogue_that_accepts_its_device_type() throws Exception {
        for (String file : List.of("one-device.csv", "device-types.csv", "device-behaviours.csv")) {
            for (ManifestRow row : rows(file)) {
                Flow flow = FlowCatalogue.find(row.flow()).orElse(null);
                assertTrue(flow != null, file + " row " + row.rowNumber() + ": the catalogue has no Flow " + row.flow());
                assertTrue(flow.acceptsDeviceType(row.deviceType()),
                        file + " row " + row.rowNumber() + ": " + row.flow() + " does not take " + row.deviceType());
            }
        }
    }

    @Test
    void device_types_runs_meter_triage_for_the_meters_and_hub_triage_for_the_hub() throws Exception {
        var rows = rows("device-types.csv");

        assertEquals(List.of("meter-triage", "meter-triage", "hub-triage"), rows.stream().map(ManifestRow::flow).toList());
    }

    @Test
    void no_device_is_in_two_files() throws Exception {
        List<String> ids = new ArrayList<>();
        for (String file : List.of("one-device.csv", "device-types.csv", "device-behaviours.csv")) {
            rows(file).forEach(r -> ids.add(r.deviceId()));
        }

        assertEquals(ids.size(), Set.copyOf(ids).size());
    }
}
