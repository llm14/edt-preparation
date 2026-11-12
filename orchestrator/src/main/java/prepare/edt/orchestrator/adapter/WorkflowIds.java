package prepare.edt.orchestrator.adapter;

/**
 * Child workflow IDs, all derived from the Batch ID, so they are stable on replay and easy to find in the Temporal UI:
 *
 *   batch-edt-manifests/sample.csv/v1            the Batch (from Batch Ingest)
 *   batch-edt-manifests/sample.csv/v1/page-0     its Pages
 *   batch-edt-manifests/sample.csv/v1/row-3      a device Flow, by row number (a device may appear twice)
 *   service-request-<Request ID>                 a Service Request (the Response Listener needs only the Request ID)
 */
final class WorkflowIds {

    private WorkflowIds() {
    }

    static String page(String batchId, int page) {
        return batchId + "/page-" + page;
    }

    static String flow(String batchId, long rowNumber) {
        return batchId + "/row-" + rowNumber;
    }
}
