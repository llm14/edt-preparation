package prepare.edt.orchestrator.adapter;

import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import org.jboss.logging.Logger;
import prepare.edt.orchestrator.entity.ManifestLocation;
import prepare.edt.orchestrator.infrastructure.LogFields;
import prepare.edt.orchestrator.usecase.ReadManifestPage;
import prepare.edt.orchestrator.usecase.ReadManifestPage.ManifestPage;
import prepare.edt.orchestrator.usecase.ReadManifestPage.NotAManifestException;

/**
 * The activity around ReadManifestPage. Logs each invalid row (number and reason, not its content) and the Page read.
 *
 * S3 errors are thrown as they are: Temporal retries the activity. A file that is not a Manifest fails without retry.
 */
public class ManifestReaderActivityImpl implements ManifestReaderActivity {

    static final String NOT_A_MANIFEST = "NotAManifest";
    private static final Logger LOG = Logger.getLogger(ManifestReaderActivityImpl.class);

    private final ReadManifestPage readManifestPage;

    public ManifestReaderActivityImpl(ReadManifestPage readManifestPage) {
        this.readManifestPage = readManifestPage;
    }

    @Override
    public PageRows readPage(Request request) {
        ManifestPage page;
        try {
            page = readManifestPage.read(new ManifestLocation(request.bucket(), request.key(), request.versionId()),
                    request.offset(), request.rows(), request.firstRowNumber());
        } catch (NotAManifestException e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), NOT_A_MANIFEST);
        }

        String workflowId = workflowId();
        for (var invalid : page.invalidRows()) {
            try (var fields = LogFields.of("event.action", "manifest-row-invalid", "edt.manifest.row", String.valueOf(invalid.rowNumber()),
                    "error.message", invalid.reason(), "temporal.workflow_id", workflowId)) {
                LOG.warn("Invalid Manifest row: skipped");
            }
        }
        try (var fields = LogFields.of("event.action", "manifest-page-read", "edt.manifest.offset", String.valueOf(request.offset()),
                "edt.manifest.next_offset", String.valueOf(page.nextOffset()), "edt.manifest.rows", String.valueOf(page.rows().size()),
                "edt.manifest.invalid_rows", String.valueOf(page.invalidRows().size()),
                "edt.manifest.end", String.valueOf(page.endOfManifest()), "temporal.workflow_id", workflowId)) {
            LOG.info("Manifest Page read");
        }
        return new PageRows(page.rows(), page.invalidRows().size(), page.nextOffset(), page.endOfManifest());
    }

    /** The calling workflow's ID, for the logs; "none" when called outside Temporal (unit tests). */
    private static String workflowId() {
        try {
            return Activity.getExecutionContext().getInfo().getWorkflowId();
        } catch (RuntimeException e) {
            return "none";
        }
    }
}
