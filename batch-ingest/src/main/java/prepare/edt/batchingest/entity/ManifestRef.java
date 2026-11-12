package prepare.edt.batchingest.entity;

/**
 * One uploaded Manifest: an object version in the Manifest bucket.
 *
 * Each upload is a new version, so re-uploading the same file is a new Manifest and starts a new Batch.
 */
public record ManifestRef(String bucket, String key, String versionId) {

    public ManifestRef {
        requireText(bucket, "bucket");
        requireText(key, "key");
        requireText(versionId, "versionId");
    }

    /**
     * The Batch workflow ID, derived from bucket, key and object version (model: "a message delivered twice starts
     * the Batch once"). Temporal refuses a second workflow with the same ID.
     */
    public String batchWorkflowId() {
        return "batch-" + bucket + "/" + key + "/" + versionId;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Manifest " + name + " is missing");
        }
    }
}
