package prepare.edt.batchingest.adapter;

/**
 * Input of the Batch workflow: where its Manifest is. The Orchestrator keeps its own copy of this contract
 * (no shared module), with the same field names, so the JSON Temporal stores maps onto it.
 */
public record BatchInput(String bucket, String key, String versionId) {
}
