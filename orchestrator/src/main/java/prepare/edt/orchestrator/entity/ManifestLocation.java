package prepare.edt.orchestrator.entity;

/** Where a Manifest is: one object version in the Manifest bucket. The Batch reads only that version. */
public record ManifestLocation(String bucket, String key, String versionId) {
}
