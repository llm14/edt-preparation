package prepare.edt.orchestrator.adapter;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.List;
import prepare.edt.orchestrator.entity.ManifestRow;

/** Manifest Reader Activity (model): reads the rows of one Page with S3 byte-range reads. */
@ActivityInterface
public interface ManifestReaderActivity {

    @ActivityMethod(name = "ReadManifestPage")
    PageRows readPage(Request request);

    /**
     * @param offset         byte where the Page starts
     * @param rows           rows per Page
     * @param firstRowNumber number of the Page's first row
     */
    record Request(String bucket, String key, String versionId, long offset, int rows, long firstRowNumber) {
    }

    /** The valid rows, how many were invalid (already logged), and where the next Page starts. */
    record PageRows(List<ManifestRow> rows, int invalidRows, long nextOffset, boolean endOfManifest) {
    }
}
