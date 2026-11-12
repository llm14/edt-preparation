package prepare.edt.orchestrator.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import prepare.edt.orchestrator.entity.ManifestLocation;
import prepare.edt.orchestrator.entity.ManifestRow;
import prepare.edt.orchestrator.usecase.ReadManifestPage.InvalidRow;
import prepare.edt.orchestrator.usecase.ReadManifestPage.ManifestPage;
import prepare.edt.orchestrator.usecase.ReadManifestPage.NotAManifestException;

public class ReadManifestPageTest {

    static final ManifestLocation MANIFEST = new ManifestLocation("edt-manifests", "sample.csv", "v1");

    public static final String CSV = """
            device_id,device_type,flow
            00-DB-12-34-56-78-90-A0,ESME,flow-1
            00-DB-12-34-56-78-90-A1,ESME,flow-1
            00-DB-12-34-56-78-90-A2,GSME,flow-2
            00-DB-12-34-56-78-90-A3,CHF,flow-3
            00-DB-12-34-56-78-90-A5,ESME,flow-1
            """;

    /** The bytes of a Manifest, served by range; records each range read. */
    public static final class InMemory implements ManifestBytes {
        final byte[] content;
        final List<long[]> reads = new ArrayList<>();

        public InMemory(String text) {
            content = text.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public byte[] read(ManifestLocation manifest, long start, int length) {
            reads.add(new long[] {start, length});
            if (start >= content.length) {
                return new byte[0];
            }
            return Arrays.copyOfRange(content, (int) start, (int) Math.min(content.length, start + length));
        }
    }

    /** Reads the whole Manifest Page by Page, as the Batch does, and returns the device IDs per Page. */
    static List<List<String>> allPages(InMemory bytes, int chunk, int rowsPerPage) throws Exception {
        var reader = new ReadManifestPage(bytes, chunk);
        List<List<String>> pages = new ArrayList<>();
        long offset = 0;
        for (int page = 0; ; page++) {
            ManifestPage read = reader.read(MANIFEST, offset, rowsPerPage, (long) page * rowsPerPage + 1);
            pages.add(read.rows().stream().map(r -> r.rowNumber() + ":" + r.deviceId().substring(21)).toList());
            offset = read.nextOffset();
            if (read.endOfManifest()) {
                return pages;
            }
        }
    }

    @ParameterizedTest(name = "chunk of {0} bytes")
    @ValueSource(ints = {1, 7, 40, 65536})
    void pages_of_rows_whatever_the_chunk_size(int chunk) throws Exception {
        assertEquals(List.of(List.of("1:A0", "2:A1"), List.of("3:A2", "4:A3"), List.of("5:A5")),
                allPages(new InMemory(CSV), chunk, 2));
    }

    @Test
    void the_next_page_starts_at_the_byte_after_the_last_line() throws Exception {
        var reader = new ReadManifestPage(new InMemory(CSV), 64);

        ManifestPage first = reader.read(MANIFEST, 0, 2, 1);

        int expected = ("device_id,device_type,flow\n" + "00-DB-12-34-56-78-90-A0,ESME,flow-1\n"
                + "00-DB-12-34-56-78-90-A1,ESME,flow-1\n").length();
        assertEquals(expected, first.nextOffset());
        assertFalse(first.endOfManifest());
        assertEquals(new ManifestRow(1, "00-DB-12-34-56-78-90-A0", "ESME", "flow-1"), first.rows().get(0));
    }

    @Test
    void only_ranges_are_read_never_the_whole_manifest() throws Exception {
        var bytes = new InMemory(CSV);

        new ReadManifestPage(bytes, 40).read(MANIFEST, 0, 1, 1);

        assertEquals(2, bytes.reads.size(), "header and first row need 64 bytes: two chunks of 40");
        assertEquals(40, bytes.reads.get(1)[0]);
    }

    @Test
    void crlf_a_bom_blank_lines_and_no_final_newline() throws Exception {
        String windows = "﻿" + CSV.replace("\n", "\r\n").replace("A1,ESME,flow-1\r\n", "A1,ESME,flow-1\r\n\r\n")
                .stripTrailing();

        assertEquals(List.of(List.of("1:A0", "2:A1"), List.of("3:A2", "4:A3"), List.of("5:A5")),
                allPages(new InMemory(windows), 7, 2));
    }

    @Test
    void invalid_rows_are_counted_in_the_page_and_skipped() throws Exception {
        String csv = CSV.replace("00-DB-12-34-56-78-90-A1,ESME", "not-a-device,ESME").replace("CHF,flow-3", "CHF");

        ManifestPage page = new ReadManifestPage(new InMemory(csv), 16).read(MANIFEST, 0, 5, 1);

        assertEquals(List.of(1L, 3L, 5L), page.rows().stream().map(ManifestRow::rowNumber).toList());
        assertEquals(List.of(new InvalidRow(2, "device_id is not a device ID"),
                new InvalidRow(4, "expected 3 columns, found 2")), page.invalidRows());
        assertTrue(page.endOfManifest() || page.nextOffset() == csv.length());
    }

    @Test
    void a_file_without_the_header_is_not_a_manifest() {
        var reader = new ReadManifestPage(new InMemory("id;type\n00-DB-12-34-56-78-90-A0;ESME\n"), 64);

        assertThrows(NotAManifestException.class, () -> reader.read(MANIFEST, 0, 2, 1));
    }

    @Test
    void past_the_last_row_the_page_is_empty_and_ends() throws Exception {
        ManifestPage page = new ReadManifestPage(new InMemory(CSV), 64).read(MANIFEST, CSV.length(), 2, 7);

        assertEquals(List.of(), page.rows());
        assertTrue(page.endOfManifest());
        assertEquals(CSV.length(), page.nextOffset());
    }
}
