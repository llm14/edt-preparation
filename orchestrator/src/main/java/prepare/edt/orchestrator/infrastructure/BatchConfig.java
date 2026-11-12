package prepare.edt.orchestrator.infrastructure;

import io.smallrye.config.ConfigMapping;

/** Settings of the Batch and Page workflows and of the Manifest Reader (prefix "edt.batch"). [CONFIRM] values. */
@ConfigMapping(prefix = "edt.batch")
public interface BatchConfig {

    /** Rows per Page. */
    int rowsPerPage();

    /** Pages of one Batch running at the same time. */
    int pagesAtATime();

    /** Device Flows running at the same time in one Page. */
    int flowsPerPage();

    /** The Batch continues as new after this many Pages. */
    int pagesPerRun();

    /** Bytes per S3 range read. */
    int readChunkBytes();
}
