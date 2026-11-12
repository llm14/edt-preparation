package prepare.edt.orchestrator.usecase;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import prepare.edt.orchestrator.entity.ManifestLocation;
import prepare.edt.orchestrator.entity.ManifestRow;
import prepare.edt.orchestrator.entity.ManifestRow.InvalidRowException;

/**
 * Reads the rows of one Page from a byte offset, with byte-range reads only (model: "reads the rows of one Page with
 * an S3 byte-range read, so no Manifest is ever loaded whole; invalid rows are counted and logged").
 *
 *   - reads chunks from the offset until it has the Page's number of rows, or the Manifest ends
 *   - a chunk can end in the middle of a line: the rest of the line comes with the next chunk
 *   - returns where the next Page starts: the byte after the last line it took
 *   - at offset 0 the first line is the header; it must be the expected one
 *   - blank lines are skipped and not counted; an invalid row is counted (it uses a place in the Page)
 */
public final class ReadManifestPage {

    private final ManifestBytes bytes;
    private final int chunkSize;

    public ReadManifestPage(ManifestBytes bytes, int chunkSize) {
        this.bytes = bytes;
        this.chunkSize = chunkSize;
    }

    /**
     * @param offset         byte where the Page starts (the start of a line)
     * @param rows           how many rows the Page takes
     * @param firstRowNumber number of the Page's first row (rows before it + 1)
     * @throws NotAManifestException when the header is not the expected one (retrying cannot help)
     */
    public ManifestPage read(ManifestLocation manifest, long offset, int rows, long firstRowNumber)
            throws NotAManifestException {
        List<ManifestRow> valid = new ArrayList<>();
        List<InvalidRow> invalid = new ArrayList<>();
        boolean expectHeader = offset == 0;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        long position = offset;          // next byte to read
        long lineEnd = offset;           // byte after the last complete line taken

        while (valid.size() + invalid.size() < rows) {
            byte[] chunk = bytes.read(manifest, position, chunkSize);
            if (chunk.length == 0) {
                // End of the Manifest. A last line without a newline still counts.
                if (line.size() > 0) {
                    expectHeader = take(text(line), expectHeader, firstRowNumber, valid, invalid);
                    lineEnd = position;
                }
                return new ManifestPage(valid, invalid, lineEnd, true);
            }
            for (int i = 0; i < chunk.length; i++) {
                if (chunk[i] != '\n') {
                    line.write(chunk[i]);
                    continue;
                }
                expectHeader = take(text(line), expectHeader, firstRowNumber, valid, invalid);
                line.reset();
                lineEnd = position + i + 1;
                if (valid.size() + invalid.size() == rows) {
                    return new ManifestPage(valid, invalid, lineEnd, false);
                }
            }
            position += chunk.length;
        }
        return new ManifestPage(valid, invalid, lineEnd, false);
    }

    /** Takes one complete line. Returns whether the header is still expected. */
    private static boolean take(String text, boolean expectHeader, long firstRowNumber,
                                List<ManifestRow> valid, List<InvalidRow> invalid) throws NotAManifestException {
        if (expectHeader) {
            String header = text.startsWith("﻿") ? text.substring(1) : text;   // a BOM is allowed
            if (!ManifestRow.HEADER.equals(header.trim())) {
                throw new NotAManifestException("the first line is not the header " + ManifestRow.HEADER);
            }
            return false;
        }
        if (text.isBlank()) {
            return false;
        }
        long rowNumber = firstRowNumber + valid.size() + invalid.size();
        try {
            valid.add(ManifestRow.parse(rowNumber, text));
        } catch (InvalidRowException e) {
            invalid.add(new InvalidRow(rowNumber, e.getMessage()));
        }
        return false;
    }

    private static String text(ByteArrayOutputStream line) {
        String text = line.toString(StandardCharsets.UTF_8);
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    /** The rows of one Page and where the next Page starts. */
    public record ManifestPage(List<ManifestRow> rows, List<InvalidRow> invalidRows, long nextOffset,
                               boolean endOfManifest) {
    }

    /** An invalid row: its number and why (no content, so it can be logged). */
    public record InvalidRow(long rowNumber, String reason) {
    }

    public static final class NotAManifestException extends Exception {
        public NotAManifestException(String message) {
            super(message);
        }
    }
}
